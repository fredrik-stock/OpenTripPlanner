package org.opentripplanner.routing;

import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.execution.ExecutorServiceExecutionStrategy;
import org.locationtech.jts.geom.Envelope;
import org.opentripplanner.index.IndexGraphQLSchema;
import org.opentripplanner.index.model.StopTimesInPattern;
import org.opentripplanner.index.model.TripTimeShort;
import org.opentripplanner.model.Agency;
import org.opentripplanner.model.FeedInfo;
import org.opentripplanner.model.FeedScopedId;
import org.opentripplanner.model.MultiModalStation;
import org.opentripplanner.model.Notice;
import org.opentripplanner.model.Operator;
import org.opentripplanner.model.Route;
import org.opentripplanner.model.Station;
import org.opentripplanner.model.Stop;
import org.opentripplanner.model.Timetable;
import org.opentripplanner.model.TimetableSnapshot;
import org.opentripplanner.model.TransitEntity;
import org.opentripplanner.model.Trip;
import org.opentripplanner.model.TripPattern;
import org.opentripplanner.model.calendar.CalendarService;
import org.opentripplanner.model.calendar.ServiceDate;
import org.opentripplanner.routing.bike_rental.BikeRentalStationService;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.services.AlertPatchService;
import org.opentripplanner.routing.vertextype.TransitStopVertex;
import org.opentripplanner.util.HttpToGraphQLMapper;

import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * This is the entry point of all API requests towards the OTP graph. A new instance of this class
 * should be created for each request. This ensures that the same TimetableSnapshot is used
 * for the duration of the request (which may involve several method calls).
 */
public class RoutingService {

  private final Graph graph;

  private TimetableSnapshot timetableSnapshot;

  // TODO Move this
  private GraphQL graphQL;

  private RoutingService(Graph graph) {
    graphQL = new GraphQL(new IndexGraphQLSchema(this).indexSchema,
        new ExecutorServiceExecutionStrategy(Executors.newCachedThreadPool(new ThreadFactoryBuilder()
            .setNameFormat("GraphQLExecutor-" + graph.routerId + "-%d")
            .build()))
    );
    this.graph = graph;
  }

  public List<StopFinder.StopAndDistance> findClosestStopsByWalking(
      double lat, double lon, int radius
  ) {
    return StopFinder.findClosestStopsByWalking(graph, lat, lon, radius);
  }

  public Map<String, Map<String, Agency>> getAgenciesForFeedId() {
    return graph.index.getAgenciesForFeedId();
  }

  public Map<FeedScopedId, Operator> getOperatorForId() {
    return graph.index.getOperatorForId();
  }

  public Map<String, FeedInfo> getFeedInfoForId() {
    return graph.index.getFeedInfoForId();
  }

  public Map<FeedScopedId, Stop> getStopForId() {
    return graph.index.getStopForId();
  }

  public Map<FeedScopedId, Trip> getTripForId() {
    return graph.index.getTripForId();
  }

  public Map<FeedScopedId, Route> getRouteForId() {
    return graph.index.getRouteForId();
  }

  public Map<Stop, TransitStopVertex> getStopVertexForStop() {
    return graph.index.getStopVertexForStop();
  }

  public Map<Trip, TripPattern> getPatternForTrip() {
    return graph.index.getPatternForTrip();
  }

  public Multimap<Route, TripPattern> getPatternsForRoute() {
    return graph.index.getPatternsForRoute();
  }

  public Multimap<Stop, TripPattern> getPatternsForStop() {
    return graph.index.getPatternsForStop();
  }

  public Map<Station, MultiModalStation> getMultiModalStationForStations() {
    return graph.index.getMultiModalStationForStations();
  }

  /** Dynamically generate the set of Routes passing though a Stop on demand. */
  public Set<Route> routesForStop(Stop stop) {
    Set<Route> routes = Sets.newHashSet();
    for (TripPattern p : getPatternsForStop().get(stop)) {
      routes.add(p.route);
    }
    return routes;
  }

  /**
   * Fetch upcoming vehicle departures from a stop. It goes though all patterns passing the stop for
   * the previous, current and next service date. It uses a priority queue to keep track of the next
   * departures. The queue is shared between all dates, as services from the previous service date
   * can visit the stop later than the current service date's services. This happens eg. with
   * sleeper trains.
   * <p>
   * TODO: Add frequency based trips
   *
   * @param stop               Stop object to perform the search for
   * @param startTime          Start time for the search. Seconds from UNIX epoch
   * @param timeRange          Searches forward for timeRange seconds from startTime
   * @param numberOfDepartures Number of departures to fetch per pattern
   * @param omitNonPickups     If true, do not include vehicles that will not pick up passengers.
   */
  public List<StopTimesInPattern> stopTimesForStop(
      Stop stop, long startTime, int timeRange, int numberOfDepartures, boolean omitNonPickups
  ) {

    return StopTimesHelper.stopTimesForStop(
        this,
        stop,
        startTime,
        timeRange,
        numberOfDepartures,
        omitNonPickups
    );
  }

  /**
   * Get a list of all trips that pass through a stop during a single ServiceDate. Useful when
   * creating complete stop timetables for a single day.
   *
   * @param stop        Stop object to perform the search for
   * @param serviceDate Return all departures for the specified date
   */
  public List<StopTimesInPattern> getStopTimesForStop(
      Stop stop, ServiceDate serviceDate, boolean omitNonPickups
  ) {
    return StopTimesHelper.getStopTimesForStop(this, stop, serviceDate, omitNonPickups);
  }

  /**
   * Returns all the patterns for a specific stop. If includeRealtimeUpdates is set, new patterns
   * added by realtime updates are added to the collection.
   */
  public Collection<TripPattern> getPatternsForStop(Stop stop, boolean includeRealtimeUpdates) {
    List<TripPattern> tripPatterns = new ArrayList<>(getPatternsForStop().get(stop));

    if (includeRealtimeUpdates && timetableSnapshot != null) {
      tripPatterns.addAll(timetableSnapshot.getPatternsForStop(stop));
    }

    return tripPatterns;
  }

  /**
   * Get the most up-to-date timetable for the given TripPattern, as of right now. There should
   * probably be a less awkward way to do this that just gets the latest entry from the resolver
   * without making a fake routing request.
   */
  public Timetable currentUpdatedTimetableForTripPattern(TripPattern tripPattern) {
    // The timetableSnapshot will be null if there's no real-time data being applied.
      if (timetableSnapshot == null) { return tripPattern.scheduledTimetable; }
    // Get the updated times for right now, which is the only reasonable default since no date is supplied.
    Calendar calendar = Calendar.getInstance();
    ServiceDate serviceDate = new ServiceDate(calendar.getTime());
    return timetableSnapshot.resolve(tripPattern, serviceDate);
  }

  public Response getGraphQLResponse(
      String query, Map<String, Object> variables, String operationName
  ) {
    ExecutionResult executionResult = graphQL.execute(query, operationName, null, variables);

    return HttpToGraphQLMapper.mapExecutionResultToHttpResponse(executionResult);
  }

  /**
   * Fetch an agency by its string ID, ignoring the fact that this ID should be scoped by a feedId.
   * This is a stopgap (i.e. hack) method for fetching agencies where no feed scope is available. I
   * am creating this method only to allow merging pull request #2032 which adds GraphQL. Note that
   * if the same agency ID is defined in several feeds, this will return one of them at random. That
   * is obviously not the right behavior. The problem is that agencies are not currently keyed on an
   * FeedScopedId object, but on separate feedId and id Strings. A real fix will involve replacing
   * or heavily modifying the OBA GTFS loader, which is now possible since we have forked it.
   */
  public Agency getAgencyWithoutFeedId(String agencyId) {
    // Iterate over the agency map for each feed.
    for (Map<String, Agency> agencyForId : getAgenciesForFeedId().values()) {
      Agency agency = agencyForId.get(agencyId);
      if (agency != null) {
        return agency;
      }
    }
    return null;
  }

  /**
   * Construct a set of all Agencies in this graph, spanning across all feed IDs. I am creating this
   * method only to allow merging pull request #2032 which adds GraphQL. This should probably be
   * done some other way, see javadoc on getAgencyWithoutFeedId.
   */
  public Set<Agency> getAllAgencies() {
    Set<Agency> allAgencies = new HashSet<>();
    for (Map<String, Agency> agencyForId : getAgenciesForFeedId().values()) {
      allAgencies.addAll(agencyForId.values());
    }
    return allAgencies;
  }

  public Collection<Notice> getNoticesByEntity(TransitEntity<?> entity) {
    // Delegate to graph
    Collection<Notice> res = graph.getNoticesByElement().get(entity);
    return res == null ? Collections.emptyList() : res;
  }

  /**
   * Get a list of all operators spanning across all feeds.
   */
  public Collection<Operator> getAllOperators() {
    return getOperatorForId().values();
  }

  public BitSet getServicesRunningForDate(ServiceDate date) {
    return graph.servicesRunning(date);
  }

  public TripPattern getTripPatternForId(String id) {
    return graph.tripPatternForId.get(id);
  }

  public Collection<TripPattern> getTripPatterns() {
    return graph.tripPatternForId.values();
  }

  public Collection<Notice> getNoticesByElement(TransitEntity<?> transitEntity) {
    return graph.getNoticesByElement().get(transitEntity);
  }

  public Collection<Notice> getNotices() {
    return graph.getNoticesByElement().values();
  }

  public List<TripTimeShort> getStopTimesForTripAndDate(Trip trip, ServiceDate serviceDate) {
    if (timetableSnapshot == null) {
      return null;
    }
    else {
      TripPattern pattern = getPatternForTrip().get(trip);
      return TripTimeShort.fromTripTimes(timetableSnapshot.resolve(pattern, serviceDate), trip);
    }
  }

  public Collection<Stop> getStopsByBoundingBox(Envelope envelope) {
    return graph.streetIndex
        .getTransitStopForEnvelope(envelope)
        .stream()
        .map(TransitStopVertex::getStop)
        .collect(Collectors.toList());
  }

  public Station getStationById(FeedScopedId id) {
    return graph.stationById.get(id);
  }

  public Collection<Station> getStations() {
    return graph.stationById.values();
  }

  public CalendarService getCalendarService() {
    return graph.getCalendarService();
  }

  public TimeZone getTimeZone() {
    return graph.getTimeZone();
  }

  public BikeRentalStationService getBikerentalStationService() {
    return graph.getService(BikeRentalStationService.class);
  }

  public AlertPatchService getSiriAlertPatchService() {
    return graph.getSiriAlertPatchService();
  }

  public MultiModalStation getMultiModalStationById(FeedScopedId feedScopedId) {
    return graph.multiModalStationById.get(feedScopedId);
  }

  public List<TripTimeShort> getTripTimesShort(Trip trip, ServiceDate serviceDate) {
    return TripTimesShortHelper.getTripTimesShort(this, trip, serviceDate);
  }

  public GraphQL getGraphQL() {
    return this.graphQL;
  }

  public TimetableSnapshot getTimetableSnapshot() {
    return timetableSnapshot;
  }

  public Graph getGraph() {
    return graph;
  }

  /**
   * Lazy-initialization of TimetableSnapshot
   * @return The same TimetableSnapshot is returned throughout the lifecycle of this object.
   */
  private TimetableSnapshot getTimeTableSnapshot() {
    if (this.timetableSnapshot == null) {
      timetableSnapshot = graph.getTimetableSnapshot();
    }
    return this.timetableSnapshot;
  }
}
