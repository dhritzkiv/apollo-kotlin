package test

import IdCacheKeyGenerator
import com.apollographql.apollo3.ApolloClient
import com.apollographql.apollo3.annotations.ApolloExperimental
import com.apollographql.apollo3.api.CustomScalarAdapters
import com.apollographql.apollo3.cache.normalized.ApolloStore
import com.apollographql.apollo3.cache.normalized.FetchPolicy
import com.apollographql.apollo3.cache.normalized.api.CacheHeaders
import com.apollographql.apollo3.cache.normalized.api.MemoryCacheFactory
import com.apollographql.apollo3.cache.normalized.fetchPolicy
import com.apollographql.apollo3.cache.normalized.refetchPolicy
import com.apollographql.apollo3.cache.normalized.store
import com.apollographql.apollo3.cache.normalized.watch
import com.apollographql.apollo3.integration.normalizer.EpisodeHeroNameQuery
import com.apollographql.apollo3.integration.normalizer.EpisodeHeroNameWithIdQuery
import com.apollographql.apollo3.integration.normalizer.HeroAndFriendsNamesWithIDsQuery
import com.apollographql.apollo3.integration.normalizer.StarshipByIdQuery
import com.apollographql.apollo3.integration.normalizer.type.Episode
import com.apollographql.apollo3.testing.QueueTestNetworkTransport
import com.apollographql.apollo3.testing.enqueueTestResponse
import com.apollographql.apollo3.testing.receiveOrTimeout
import com.apollographql.apollo3.testing.runTest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

@OptIn(ApolloExperimental::class)
class WatcherTest {
  private lateinit var apolloClient: ApolloClient
  private lateinit var store: ApolloStore

  private fun setUp() {
    store = ApolloStore(MemoryCacheFactory(), cacheKeyGenerator = IdCacheKeyGenerator)
    apolloClient = ApolloClient.Builder().networkTransport(QueueTestNetworkTransport()).store(store).build()
  }

  private val episodeHeroNameData = EpisodeHeroNameQuery.Data(EpisodeHeroNameQuery.Hero("R2-D2"))
  private val episodeHeroNameChangedData = EpisodeHeroNameQuery.Data(EpisodeHeroNameQuery.Hero("Artoo"))
  private val episodeHeroNameChangedTwoData = EpisodeHeroNameQuery.Data(EpisodeHeroNameQuery.Hero("ArTwo"))

  private val episodeHeroNameWithIdData = EpisodeHeroNameWithIdQuery.Data(EpisodeHeroNameWithIdQuery.Hero("2001", "R2-D2"))


  private val heroAndFriendsNamesWithIDsData = HeroAndFriendsNamesWithIDsQuery.Data(
      HeroAndFriendsNamesWithIDsQuery.Hero("2001", "R2-D2", listOf(
          HeroAndFriendsNamesWithIDsQuery.Friend("1000", "Luke Skywalker"),
          HeroAndFriendsNamesWithIDsQuery.Friend("1002", "Han Solo"),
          HeroAndFriendsNamesWithIDsQuery.Friend("1003", "Leia Organa"),
      )))
  private val heroAndFriendsNamesWithIDsNameChangedData = HeroAndFriendsNamesWithIDsQuery.Data(
      HeroAndFriendsNamesWithIDsQuery.Hero("1000", "Luke Skywalker", listOf(
          HeroAndFriendsNamesWithIDsQuery.Friend("2001", "Artoo"),
          HeroAndFriendsNamesWithIDsQuery.Friend("1002", "Han Solo"),
          HeroAndFriendsNamesWithIDsQuery.Friend("1003", "Leia Organa"),
      )))

  /**
   * Executing the same query out of band should update the watcher
   */
  @Test
  fun sameQueryTriggersWatcher() = runTest(before = { setUp() }) {
    val query = EpisodeHeroNameQuery(Episode.EMPIRE)
    val channel = Channel<EpisodeHeroNameQuery.Data?>()

    // The first query should get a "R2-D2" name
    apolloClient.enqueueTestResponse(query, episodeHeroNameData)
    val job = launch {
      apolloClient.query(query).watch().collect {
        channel.send(it.data)
      }
    }

    assertEquals(channel.receiveOrTimeout()?.hero?.name, "R2-D2")

    // Another newer call gets updated information with "Artoo"
    apolloClient.enqueueTestResponse(query, episodeHeroNameChangedData)
    apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()

    apolloClient.query(query).toFlow()
        .catch {
          // Handle errors here
        }
        .onCompletion {
          emitAll(apolloClient.query(query).watch())
        }
    assertEquals(channel.receiveOrTimeout()?.hero?.name, "Artoo")

    job.cancel()
  }


  /**
   * Writing to the store out of band should update the watcher
   */
  @Test
  fun storeWriteTriggersWatcher() = runTest(before = { setUp() }) {
    val channel = Channel<EpisodeHeroNameWithIdQuery.Data?>()
    val operation = EpisodeHeroNameWithIdQuery(Episode.EMPIRE)
    apolloClient.enqueueTestResponse(operation, episodeHeroNameWithIdData)
    val job = launch {
      apolloClient.query(operation).watch().collect {
        channel.send(it.data)
      }
    }

    assertEquals(channel.receiveOrTimeout()?.hero?.name, "R2-D2")

    // Someone writes to the store directly
    val data = EpisodeHeroNameWithIdQuery.Data(
        EpisodeHeroNameWithIdQuery.Hero(
            "2001",
            "Artoo"
        )
    )

    store.writeOperation(operation, data, CustomScalarAdapters.Empty, CacheHeaders.NONE, true)

    assertEquals(channel.receiveOrTimeout()?.hero?.name, "Artoo")

    job.cancel()
  }

  /**
   * A new query updates the store with data that is the same as the one originally seen by the watcher
   */
  @Test
  fun noChangeSameQuery() = runTest(before = { setUp() }) {
    val query = EpisodeHeroNameQuery(Episode.EMPIRE)
    val channel = Channel<EpisodeHeroNameQuery.Data?>()

    // The first query should get a "R2-D2" name
    apolloClient.enqueueTestResponse(query, episodeHeroNameData)
    val job = launch {
      apolloClient.query(query).watch().collect {
        channel.send(it.data)
      }
    }

    assertEquals(channel.receiveOrTimeout()?.hero?.name, "R2-D2")

    // Another newer call gets the same name (R2-D2)
    apolloClient.enqueueTestResponse(query, episodeHeroNameData)
    apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()

    channel.assertEmpty()

    job.cancel()
  }

  /**
   * A new query that contains overlapping fields with the watched query should trigger the watcher
   */
  @Test
  fun differentQueryTriggersWatcher() = runTest(before = { setUp() }) {
    val channel = Channel<EpisodeHeroNameWithIdQuery.Data?>()

    // The first query should get a "R2-D2" name
    val episodeHeroNameWithIdQuery = EpisodeHeroNameWithIdQuery(Episode.EMPIRE)
    apolloClient.enqueueTestResponse(episodeHeroNameWithIdQuery, episodeHeroNameWithIdData)
    val job = launch {
      apolloClient.query(episodeHeroNameWithIdQuery).watch().collect {
        channel.send(it.data)
      }
    }

    assertEquals(channel.receiveOrTimeout()?.hero?.name, "R2-D2")

    // Another newer call gets updated information with "Artoo"
    val heroAndFriendsNamesWithIDsQuery = HeroAndFriendsNamesWithIDsQuery(Episode.NEWHOPE)
    apolloClient.enqueueTestResponse(heroAndFriendsNamesWithIDsQuery, heroAndFriendsNamesWithIDsNameChangedData)
    apolloClient.query(heroAndFriendsNamesWithIDsQuery)
        .fetchPolicy(FetchPolicy.NetworkOnly)
        .execute()


    assertEquals(channel.receiveOrTimeout()?.hero?.name, "Artoo")

    job.cancel()
  }

  /**
   * Same as noChangeSameQuery with different queries
   */
  @Test
  fun noChangeDifferentQuery() = runTest(before = { setUp() }) {
    val channel = Channel<EpisodeHeroNameQuery.Data?>()

    // The first query should get a "R2-D2" name
    val episodeHeroNameQuery = EpisodeHeroNameQuery(Episode.EMPIRE)
    apolloClient.enqueueTestResponse(episodeHeroNameQuery, episodeHeroNameData)
    val job = launch {
      apolloClient.query(episodeHeroNameQuery).watch().collect {
        channel.send(it.data)
      }
    }

    assertEquals(channel.receive()?.hero?.name, "R2-D2")

    // Another newer call gets the same information
    val heroAndFriendsNamesWithIDsQuery = HeroAndFriendsNamesWithIDsQuery(Episode.NEWHOPE)
    apolloClient.enqueueTestResponse(heroAndFriendsNamesWithIDsQuery, heroAndFriendsNamesWithIDsData)
    apolloClient.query(heroAndFriendsNamesWithIDsQuery)
        .fetchPolicy(FetchPolicy.NetworkOnly)
        .execute()

    channel.assertEmpty()

    job.cancel()
  }

  /**
   * A test to test refetching with a NetworkOnly refetchPolicy. On every change, the watcher should get new information
   * from the network
   */
  @Test
  fun networkRefetchPolicy() = runTest(before = { setUp() }) {
    val channel = Channel<EpisodeHeroNameQuery.Data?>()

    // The first query should get a "R2-D2" name
    val episodeHeroNameQuery = EpisodeHeroNameQuery(Episode.EMPIRE)
    apolloClient.enqueueTestResponse(episodeHeroNameQuery, episodeHeroNameData)
    val job = launch {
      apolloClient.query(episodeHeroNameQuery)
          .fetchPolicy(FetchPolicy.NetworkOnly)
          .refetchPolicy(FetchPolicy.NetworkOnly)
          .watch().collect {
            channel.send(it.data)
          }
    }

    assertEquals(channel.receiveOrTimeout()?.hero?.name, "R2-D2")

    // Enqueue 2 responses.
    // - The first one will be for the query just below and contains "Artoo"
    // - The second one will be for the watcher refetch and contains "ArTwo"
    apolloClient.enqueueTestResponse(episodeHeroNameQuery, episodeHeroNameChangedData)
    apolloClient.enqueueTestResponse(episodeHeroNameQuery, episodeHeroNameChangedTwoData)
    // - Because the network only watcher will also store in the cache a different name value, it will trigger itself again
    // Enqueue a stable response to avoid errors during tests
    apolloClient.enqueueTestResponse(episodeHeroNameQuery, episodeHeroNameChangedTwoData)
    val response = apolloClient.query(episodeHeroNameQuery)
        .fetchPolicy(FetchPolicy.NetworkOnly)
        .execute()

    assertEquals(response.data?.hero?.name, "Artoo")

    // The watcher should see "ArTwo"
    assertEquals(channel.receiveOrTimeout()?.hero?.name, "ArTwo")

    job.cancel()
  }


  @Test
  fun nothingReceivedWhenCancelled() = runTest(before = { setUp() }) {
    val channel = Channel<EpisodeHeroNameQuery.Data?>()

    val query = EpisodeHeroNameQuery(Episode.EMPIRE)
    apolloClient.enqueueTestResponse(query, episodeHeroNameData)
    val job = launch {
      apolloClient.query(query)
          .fetchPolicy(FetchPolicy.NetworkOnly)
          .refetchPolicy(FetchPolicy.NetworkOnly)
          .watch()
          .collect {
            channel.send(it.data)
          }
    }
    job.cancelAndJoin()

    channel.assertEmpty()
  }

  private suspend fun <D> Channel<D>.assertEmpty() {
    /**
     * We might want to change the code to have something that can model emptyness in a  more precise way but
     * This should work in the very vast majority of cases
     */
    try {
      receiveOrTimeout()
      fail("Nothing should be received")
    } catch (_: TimeoutCancellationException) {
    }
  }

  /**
   * Doing the initial query as cache only will detect when the query becomes available
   */
  @Test
  fun cacheOnlyFetchPolicy() = runTest(before = { setUp() }) {
    val query = EpisodeHeroNameQuery(Episode.EMPIRE)
    val channel = Channel<EpisodeHeroNameQuery.Data?>()

    // This will initially miss as the cache should be empty
    val job = launch {
      apolloClient.query(query)
          .fetchPolicy(FetchPolicy.CacheOnly)
          .watch()
          .collect {
            channel.send(it.data)
          }
    }

    // Another newer call gets updated information with "R2-D2"
    apolloClient.enqueueTestResponse(query, episodeHeroNameData)
    apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()

    assertEquals(channel.receiveOrTimeout()?.hero?.name, "R2-D2")

    job.cancel()
  }

  @Test
  fun queryWatcherWithCacheOnlyNeverGoesToTheNetwork() = runTest(before = { setUp() }) {
    val channel = Channel<EpisodeHeroNameQuery.Data?>(capacity = Channel.UNLIMITED)
    val job = launch {

      apolloClient.query(EpisodeHeroNameQuery(Episode.EMPIRE))
          .fetchPolicy(FetchPolicy.CacheOnly)
          .refetchPolicy(FetchPolicy.CacheOnly)
          .watch().collect {
            channel.send(it.data)
          }
    }

    // execute a query that doesn't share any key with the main query
    // that will trigger a refetch that shouldn't throw
    apolloClient.query(StarshipByIdQuery("Starship1"))

    // There should be no data
    channel.assertEmpty()

    job.cancel()
  }
}
