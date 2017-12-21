package killrvideo.service;

import com.datastax.driver.core.*;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.driver.dse.DseSession;
import com.datastax.driver.dse.graph.GraphNode;
import com.datastax.driver.dse.graph.GraphResultSet;
import com.datastax.driver.dse.graph.GraphStatement;
import com.datastax.driver.dse.graph.Vertex;
import com.datastax.driver.mapping.Mapper;
import com.datastax.driver.mapping.Result;
import com.datastax.dse.graph.api.DseGraph;

import com.google.common.collect.Sets;
import com.google.common.eventbus.Subscribe;
import io.grpc.stub.StreamObserver;

import killrvideo.common.CommonTypes.Uuid;
import killrvideo.entity.Schema;
import killrvideo.entity.Video;
import killrvideo.graph.KillrVideoTraversal;
import killrvideo.graph.KillrVideoTraversalSource;
import killrvideo.ratings.events.RatingsEvents.UserRatedVideo;
import killrvideo.suggested_videos.SuggestedVideoServiceGrpc.AbstractSuggestedVideoService;
import killrvideo.suggested_videos.SuggestedVideosService.*;
import killrvideo.user_management.events.UserManagementEvents.UserCreated;
import killrvideo.utils.FutureUtils;
import killrvideo.utils.TypeConverter;
import killrvideo.validation.KillrVideoInputValidator;
import killrvideo.video_catalog.events.VideoCatalogEvents.YouTubeVideoAdded;

import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import static killrvideo.graph.KillrVideoTraversalConstants.VERTEX_USER;
import static killrvideo.graph.KillrVideoTraversalConstants.VERTEX_VIDEO;
import static killrvideo.graph.__.*;
import static killrvideo.utils.ExceptionUtils.mergeStackTrace;

@Service
public class SuggestedVideosService extends AbstractSuggestedVideoService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SuggestedVideosService.class);

    @Inject
    Mapper<Video> videoMapper;

    @Inject
    DseSession dseSession;

    @Inject
    KillrVideoTraversalSource killr;

    @Inject
    KillrVideoInputValidator validator;

    private String videosTableName;
    private PreparedStatement getRelatedVideos_getVideosPrepared;

    @PostConstruct
    public void init(){
        videosTableName = videoMapper.getTableMetadata().getName();

        /**
         * Use DSE Search against the tags column from the videos table to
         * find our related videos
         */
        getRelatedVideos_getVideosPrepared = dseSession.prepare(
                QueryBuilder
                        .select().all()
                        .from(Schema.KEYSPACE, videosTableName)
                        .where(QueryBuilder.eq("solr_query", QueryBuilder.bindMarker()))
        ).setConsistencyLevel(ConsistencyLevel.LOCAL_ONE);
    }

    @Override
    public void getRelatedVideos(GetRelatedVideosRequest request, StreamObserver<GetRelatedVideosResponse> responseObserver) {

        LOGGER.debug("Start getting related videos");

        if (!validator.isValid(request, responseObserver)) {
            return;
        }

        final Uuid videoIdUuid = request.getVideoId();
        final UUID videoId = UUID.fromString(videoIdUuid.getValue());

        final GetRelatedVideosResponse.Builder builder = GetRelatedVideosResponse.newBuilder()
                .setVideoId(videoIdUuid);

        /**
         * Load the source video asynchronously
         */
        final CompletableFuture<Video> videoFuture =
                FutureUtils.buildCompletableFuture(videoMapper.getAsync(videoId))
                        .handle((video, ex) -> {
                            if (video != null) {
                                return video;

                            } else if (ex != null) {
                                LOGGER.error(this.getClass().getName() + ".getRelatedVideos()_videoFuture Exception getting related videos: " + mergeStackTrace(ex));

                                returnNoResult(responseObserver, builder);
                                return null;

                            } else {
                                LOGGER.warn("Video with id " + videoId + " was not found in getRelatedVideos()_videoFuture");

                                returnNoResult(responseObserver, builder);
                                return null;
                            }
                        });


        /**
         * If we get to this point we have our video, now construct
         * the search query and fire it off asynchronously
         */
        CompletableFuture<Result<Video>> relatedVideosFuture = videoFuture.thenCompose(video -> {
            Boolean isSearchTerms = true;
            final StringBuilder solrQuery = new StringBuilder();
            final String replaceFind = "\"";
            final String replaceWith = "\\\"";

            /**
             * Check to see if any tags exist, if not use the video
             * name to find related videos
             */
            if (CollectionUtils.isEmpty(video.getTags())) {
                /**
                 * Get the VIDEO NAME, check for any quotes, and
                 * replace them with \" or the solr_query JSON parser will
                 * complain
                 */
                String videoName = video.getName()
                        .replaceAll(replaceFind, Matcher.quoteReplacement(replaceWith));

                /**
                 * If both tags and the video name are empty we have no search terms
                 */
                if (videoName.isEmpty()) {
                    isSearchTerms = false;

                } else {
                    /**
                     * Now append the VIDEO NAME from above into our solr_query
                     * search along with "paging":"driver" to ensure we dynamically
                     * enable pagination regardless of our nodes dse.yaml setting.
                     * https://docs.datastax.com/en/dse/5.1/dse-dev/datastax_enterprise/search/cursorsDeepPaging.html#cursorsDeepPaging__srchCursorCQL
                     */
                    //TODO: Replace edismax parser with DSE Search equivalent
                    solrQuery
                            .append("{\"q\":\"{!edismax qf=\\\"name^10 tags description^2\\\"}")
                            .append(videoName)
                            .append("\", \"paging\":\"driver\"}");
                }

            } else {
                /**
                 * Grab any TAGS, join each tag in the collection with ",",
                 * check for any quotes, and replace them with \" or the solr_query
                 * JSON parser will complain.  Append all of this into our
                 * solr_query search along with "paging":"driver" as explained above.
                 */
                //TODO: Replace edismax parser with DSE Search equivalent
                String tags = video.getTags().stream().collect(Collectors.joining((",")))
                        .replaceAll(replaceFind, Matcher.quoteReplacement(replaceWith));
                solrQuery
                        .append("{\"q\":\"{!edismax qf=\\\"name tags description\\\"}")
                        .append(tags)
                        .append("\", \"paging\":\"driver\"}");
            }

            if (isSearchTerms) {
                LOGGER.debug("getRelatedVideos() solr_query is : " + solrQuery);
                BoundStatement statement = getRelatedVideos_getVideosPrepared.bind()
                        .setString("solr_query", solrQuery.toString());

                statement
                        .setFetchSize(request.getPageSize());

                return FutureUtils.buildCompletableFuture(videoMapper.mapAsync(dseSession.executeAsync(statement)));

            } else {
                /**
                 * We have no tags or video name, so essentially do nothing
                 * as there is nothing we can search
                 */
                returnNoResult(responseObserver, builder);
                return null;
            }
        });

        /**
         * Get the results from our search query and send them
         * back to the UI
         */
        relatedVideosFuture
                .whenComplete((videos, ex) -> {
                    if (videos != null) {
                        int remaining = videos.getAvailableWithoutFetching();
                        for (Video video : videos) {
                            SuggestedVideoPreview preview = video.toSuggestedVideoPreview();

                            if (!preview.getVideoId().equals(videoIdUuid)) {
                                builder.addVideos(preview);
                            }

                            if (--remaining == 0) {
                                break;
                            }
                        }

                        responseObserver.onNext(builder.build());
                        responseObserver.onCompleted();

                        LOGGER.debug(String.format("End getting related videos with %s videos", builder.getVideosBuilderList().size()));

                    } else if (ex != null) {
                        returnNoResult(responseObserver, builder);
                        LOGGER.error(this.getClass().getName() + ".getRelatedVideos()_relatedVideosFuture Exception getting related videos: " + mergeStackTrace(ex));
                    }
                });
    }

    private void returnNoResult(StreamObserver<GetRelatedVideosResponse> responseObserver, GetRelatedVideosResponse.Builder builder) {
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();

        LOGGER.debug("End getting related videos with 0 videos");
    }

    @Override
    public void getSuggestedForUser(GetSuggestedForUserRequest request, StreamObserver<GetSuggestedForUserResponse> responseObserver) {

        LOGGER.debug("Start getting suggested videos for user");

        final Uuid userId = request.getUserId();
        final GetSuggestedForUserResponse.Builder builder = GetSuggestedForUserResponse.newBuilder();
        builder.setUserId(userId);

        final List<SuggestedVideoPreview> result = new ArrayList<>();
        final String userIdString = userId.getValue();

        try {
            /**
             * Use our KillrVideo DSL (Domain Specific Language ->
             * http://docs.datastax.com/en/developer/java-driver-dse/1.4/manual/tinkerpop/#gremlin-domain-specific-languages-dsl)
             * to first traverse our current user and then grab any recommended videos
             * from our recommendation engine.
             *
             * Parameters for recommendByUserRating are as follows:
             * number of recommendations - the number of recommended movies to return
             * min rating - the minimum rating to allow for
             * number of ratings to sample - the number of global user ratings to sample (smaller means faster traversal)
             * local user ratings to sample - the number of local user ratings to limit by
             */
            GraphStatement gStatement = DseGraph.statementFromTraversal(killr.users(userIdString)
                    .recommendByUserRating(100, 4, 250, 10)
            );

            CompletableFuture<GraphResultSet> future = FutureUtils.buildCompletableFuture(dseSession.executeGraphAsync(gStatement));

            future.whenComplete((vertices, ex) -> {
                if (vertices != null) {
                    List<GraphNode> videosAndUser = vertices.all();
                    for (GraphNode node : videosAndUser) {
                        Vertex v = node.get(VERTEX_VIDEO).asVertex();
                        Vertex u = node.get(VERTEX_USER).asVertex();

                        result.add(
                                SuggestedVideoPreview.newBuilder()
                                        .setAddedDate(TypeConverter.instantToTimeStamp(v.getProperty("added_date").getValue().as(Instant.class)))
                                        .setName(v.getProperty("name").getValue().asString())
                                        .setPreviewImageLocation(v.getProperty("preview_image_location").getValue().asString())
                                        .setUserId(TypeConverter.uuidToUuid(u.getId().get("userId").as(UUID.class)))
                                        .setVideoId(TypeConverter.uuidToUuid(v.getId().get("videoId").as(UUID.class)))
                                        .build()
                        );
                    }
                    // Add suggested videos...
                    builder.addAllVideos(result);
                    responseObserver.onNext(builder.build());
                    responseObserver.onCompleted();
                } else {
                    LOGGER.warn("Exception in SuggestedVideosService.getSuggestedForUser recommendByUserRating() recommendation traversal: " + ex);
                }
            });

        } catch (Exception ex) {
            LOGGER.error("Exception in SuggestedVideosService.getSuggestedForUser: " + ex);
        }

        LOGGER.debug("End getting suggested videos for user");
    }

    /**
     * Make @Subscribe subscriber magic happen anytime a youTube video is added from
     * VideoCatalogService.submitYouTubeVideo() with a call to eventBus.post().
     * We use this to create entries in our graph database for use with our
     * SuggestedVideos recommendation service which is why this exists here.
     * @param youTubeVideoAdded
     */
    @Subscribe
    public void handle(YouTubeVideoAdded youTubeVideoAdded) {
        final String className = this.getClass().getName();

        LOGGER.debug("Start handling YouTubeVideoAdded for " + className);

        final UUID userId = UUID.fromString(youTubeVideoAdded.getUserId().getValue());
        final UUID videoId = UUID.fromString(youTubeVideoAdded.getVideoId().getValue());
        final HashSet<String> tags = Sets.newHashSet(youTubeVideoAdded.getTagsList());
        final String name = youTubeVideoAdded.getName();
        final String previewImageLocation = youTubeVideoAdded.getPreviewImageLocation();
        final String description = youTubeVideoAdded.getDescription();
        Date addedDate = Date.from(Instant.ofEpochSecond(youTubeVideoAdded.getAddedDate().getSeconds(), youTubeVideoAdded.getTimestamp().getNanos()));
        Date taggedDate = Date.from(Instant.ofEpochSecond(youTubeVideoAdded.getTimestamp().getSeconds(), youTubeVideoAdded.getTimestamp().getNanos()));

        /**
         * Below we are using our KillrVideoTraversal DSL (Domain Specific Language)
         * to create our video vertex, then within add() we connect up the user responsible
         * for uploading the video with the "uploaded" edge, and then follow up with
         * any and all tags using the "taggedWith" edge.  Since we may have multiple
         * tags make sure to loop through and get them all in there.
         *
         * Also note the use of add().  Take a look at Stephen's blog here
         * -> https://www.datastax.com/dev/blog/gremlin-dsls-in-java-with-dse-graph for more information.
         * This essentially allows us to chain multiple commands (uploaded and (n * taggedWith) in this case)
         * while "preserving" our initial video traversal position.  Since the video vertex passes
         * through each step we do not need to worry about traversing back to video for each step
         * in the chain.
         */
        final KillrVideoTraversal traversal =
                killr.video(videoId, name, addedDate, description, previewImageLocation)
                        .add(uploaded(userId));

        tags.forEach(tag -> {
            traversal.add(taggedWith(tag, taggedDate));
        });

        /**
         * Now that our video is successfully applied lets
         * insert that video into our graph for the recommendation engine
         */
        CompletableFuture<GraphResultSet> videoVertexFuture =
                FutureUtils.buildCompletableFuture(dseSession.executeGraphAsync(
                        DseGraph.statementFromTraversal(traversal))
                );

        videoVertexFuture.whenComplete((graphResultSet, ex) -> {
            if (graphResultSet != null) {
                LOGGER.debug("Added video vertex, uploaded, and taggedWith edges: " + graphResultSet.all());

            }  else {
                //TODO: Potentially add some robustness code here
                LOGGER.warn("Error handling YouTubeVideoAdded for graph: " + ex);
            }
        });
    }

    /**
     * Make @Subscribe subscriber magic happen anytime a user is created from
     * UserManagementService.createUser() with a call to eventBus.post().
     * We use this to create entries in our graph database for use with our
     * SuggestedVideos recommendation service which is why this exists here.
     * @param userCreated
     */
    @Subscribe
    public void handle(UserCreated userCreated) {
        final String className = this.getClass().getName();

        LOGGER.debug("Start handling UserCreated for " + className);

        /**
         * This will create a user vertex in our graph if it does not already exist
         */
        GraphStatement gStatement = DseGraph.statementFromTraversal(
                killr.user(
                        UUID.fromString(userCreated.getUserId().getValue()),
                        userCreated.getEmail(),
                        TypeConverter.dateFromTimestamp(userCreated.getTimestamp())
                ));

        CompletableFuture<GraphResultSet> graphResultFuture =
                FutureUtils.buildCompletableFuture(dseSession.executeGraphAsync(gStatement));

        graphResultFuture.whenComplete((graphResultSet, ex) -> {
            if (graphResultSet != null) {
                LOGGER.debug("Added user vertex: " + graphResultSet.one());

            } else {
                //TODO: Potentially add some robustness code here
                LOGGER.warn("Error creating user vertex: " + ex);
            }
        });
    }

    /**
     * Make @Subscribe subscriber magic happen anytime a user rates a video
     * RatingsService.rateVideo() with a call to eventBus.post().
     * We use this to create entries in our graph database for use with our
     * SuggestedVideos recommendation service which is why this exists here.
     * @param userRatedVideo
     */
    @Subscribe
    public void handle(UserRatedVideo userRatedVideo) {
        final String className = this.getClass().getName();

        LOGGER.debug("Start handling UserRatedVideo for " + className);

        /**
         * Note that if either the user or video does not exist in the graph
         * the rating will not be applied nor will the user or video be
         * automatically created in this case.  This assumes both the user and video
         * already exist.
         */
        final KillrVideoTraversal traversal =
                killr.videos(userRatedVideo.getVideoId().getValue())
                        .add(rated(UUID.fromString(userRatedVideo.getUserId().getValue()), userRatedVideo.getRating()));

        final CompletableFuture<GraphResultSet> ratingFuture =
                FutureUtils.buildCompletableFuture(dseSession.executeGraphAsync(DseGraph.statementFromTraversal(traversal)));

        ratingFuture.whenComplete((graphResultSet, ex) -> {
            if (graphResultSet != null) {
                LOGGER.debug("Added rating between user and video: " + graphResultSet.one());

            } else {
                //TODO: Potentially add some robustness code here
                LOGGER.warn("Error Adding rating between user and video: " + ex);
            }
        });
    }
}
