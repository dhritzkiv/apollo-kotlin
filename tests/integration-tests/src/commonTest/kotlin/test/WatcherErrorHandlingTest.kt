package test

import IdCacheKeyGenerator
import com.apollographql.apollo3.ApolloClient
import com.apollographql.apollo3.annotations.ApolloExperimental
import com.apollographql.apollo3.cache.normalized.ApolloStore
import com.apollographql.apollo3.cache.normalized.FetchPolicy
import com.apollographql.apollo3.cache.normalized.WatchErrorHandling
import com.apollographql.apollo3.cache.normalized.api.MemoryCacheFactory
import com.apollographql.apollo3.cache.normalized.fetchPolicy
import com.apollographql.apollo3.cache.normalized.refetchPolicy
import com.apollographql.apollo3.cache.normalized.store
import com.apollographql.apollo3.cache.normalized.watch
import com.apollographql.apollo3.exception.ApolloCompositeException
import com.apollographql.apollo3.exception.ApolloHttpException
import com.apollographql.apollo3.exception.CacheMissException
import com.apollographql.apollo3.integration.normalizer.EpisodeHeroNameQuery
import com.apollographql.apollo3.integration.normalizer.type.Episode
import com.apollographql.apollo3.mockserver.MockServer
import com.apollographql.apollo3.mockserver.enqueue
import com.apollographql.apollo3.testing.receiveOrTimeout
import com.apollographql.apollo3.testing.runTest
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import testFixtureToUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

@OptIn(ApolloExperimental::class)
class WatcherErrorHandlingTest {
  private lateinit var mockServer: MockServer
  private lateinit var apolloClient: ApolloClient
  private lateinit var store: ApolloStore

  private suspend fun setUp() {
    store = ApolloStore(MemoryCacheFactory(), cacheKeyGenerator = IdCacheKeyGenerator)
    mockServer = MockServer()
    apolloClient = ApolloClient.Builder().serverUrl(mockServer.url()).store(store).build()
  }

  private suspend fun tearDown() {
    mockServer.stop()
  }

  @Test
  fun fetchIgnoreAllErrors() = runTest(before = { setUp() }, after = { tearDown() }) {
    val channel = Channel<EpisodeHeroNameQuery.Data?>()
    val jobs = mutableListOf<Job>()

    jobs += launch {
      apolloClient.query(EpisodeHeroNameQuery(Episode.EMPIRE))
          .fetchPolicy(FetchPolicy.CacheFirst)
          .watch()
          .collect {
            channel.send(it.data)
          }
    }

    jobs += launch {
      apolloClient.query(EpisodeHeroNameQuery(Episode.EMPIRE))
          .fetchPolicy(FetchPolicy.CacheOnly)
          .watch()
          .collect {
            channel.send(it.data)
          }
    }

    jobs += launch {
      apolloClient.query(EpisodeHeroNameQuery(Episode.EMPIRE))
          .fetchPolicy(FetchPolicy.NetworkFirst)
          .watch()
          .collect {
            channel.send(it.data)
          }
    }

    jobs += launch {
      apolloClient.query(EpisodeHeroNameQuery(Episode.EMPIRE))
          .fetchPolicy(FetchPolicy.NetworkOnly)
          .watch()
          .collect {
            channel.send(it.data)
          }
    }

    channel.assertEmpty()
    jobs.forEach { it.cancel() }
  }

  @Test
  fun refetchIgnoreAllErrors() = runTest(before = { setUp() }, after = { tearDown() }) {
    val channel = Channel<EpisodeHeroNameQuery.Data?>()

    val query = EpisodeHeroNameQuery(Episode.EMPIRE)

    // The first query should get a "R2-D2" name
    val job = launch {
      mockServer.enqueue(testFixtureToUtf8("EpisodeHeroNameResponseWithId.json"))
      apolloClient.query(query)
          .fetchPolicy(FetchPolicy.NetworkOnly)
          .refetchPolicy(FetchPolicy.NetworkOnly)
          .watch()
          .collect {
            channel.send(it.data)
          }
    }
    assertEquals(channel.receiveOrTimeout()?.hero?.name, "R2-D2")

    // Another newer call gets updated information with "Artoo"
    // Due to .refetchPolicy(FetchPolicy.NetworkOnly), a subsequent call will be executed in watch()
    // we didn't enqueue anything in the mockServer so a network exception is thrown, and ignored by default
    mockServer.enqueue(testFixtureToUtf8("EpisodeHeroNameResponseNameChange.json"))
    apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()

    channel.assertEmpty()
    job.cancel()
  }


  @Test
  fun fetchThrowCacheErrors() = runTest(before = { setUp() }, after = { tearDown() }) {
    assertFailsWith(CacheMissException::class) {
      apolloClient.query(EpisodeHeroNameQuery(Episode.EMPIRE))
          .fetchPolicy(FetchPolicy.CacheFirst)
          .watch(WatchErrorHandling.THROW_CACHE_ERRORS)
          .first()
    }

    assertFailsWith(CacheMissException::class) {
      apolloClient.query(EpisodeHeroNameQuery(Episode.EMPIRE))
          .fetchPolicy(FetchPolicy.CacheOnly)
          .watch(WatchErrorHandling.THROW_CACHE_ERRORS)
          .first()
    }

    assertFailsWith(CacheMissException::class) {
      apolloClient.query(EpisodeHeroNameQuery(Episode.EMPIRE))
          .fetchPolicy(FetchPolicy.NetworkFirst)
          .watch(WatchErrorHandling.THROW_CACHE_ERRORS)
          .first()
    }
  }

  @Test
  fun fetchThrowNetworkErrors() = runTest(before = { setUp() }, after = { tearDown() }) {
    assertFailsWith(ApolloHttpException::class) {
      apolloClient.query(EpisodeHeroNameQuery(Episode.EMPIRE))
          .fetchPolicy(FetchPolicy.NetworkFirst)
          .watch(WatchErrorHandling.THROW_NETWORK_ERRORS)
          .first()
    }

    assertFailsWith(ApolloHttpException::class) {
      apolloClient.query(EpisodeHeroNameQuery(Episode.EMPIRE))
          .fetchPolicy(FetchPolicy.NetworkOnly)
          .watch(WatchErrorHandling.THROW_NETWORK_ERRORS)
          .first()
    }

    assertFailsWith(ApolloHttpException::class) {
      apolloClient.query(EpisodeHeroNameQuery(Episode.EMPIRE))
          .fetchPolicy(FetchPolicy.CacheFirst)
          .watch(WatchErrorHandling.THROW_NETWORK_ERRORS)
          .first()
    }
  }

  @Test
  fun refetchThrowNetworkErrors() = runTest(before = { setUp() }, after = { tearDown() }) {
    val channel = Channel<EpisodeHeroNameQuery.Data?>()

    val query = EpisodeHeroNameQuery(Episode.EMPIRE)

    var throwable: Throwable? = null

    // The first query should get a "R2-D2" name
    val job = launch {
      mockServer.enqueue(testFixtureToUtf8("EpisodeHeroNameResponseWithId.json"))
      apolloClient.query(query)
          .fetchPolicy(FetchPolicy.NetworkOnly)
          .refetchPolicy(FetchPolicy.NetworkOnly)
          .watch(refetchErrorHandling = WatchErrorHandling.THROW_NETWORK_ERRORS)
          .catch { throwable = it }
          .collect {
            channel.send(it.data)
          }
    }
    assertEquals(channel.receiveOrTimeout()?.hero?.name, "R2-D2")

    // Another newer call gets updated information with "Artoo"
    // Due to .refetchPolicy(FetchPolicy.NetworkOnly), a subsequent call will be executed in watch()
    // we didn't enqueue anything in mockServer so a network exception is thrown, and surfaced due to THROW_NETWORK_ERRORS
    mockServer.enqueue(testFixtureToUtf8("EpisodeHeroNameResponseNameChange.json"))
    apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()

    channel.assertEmpty()

    assertIs<ApolloHttpException>(throwable)

    job.cancel()
  }

  @Test
  fun fetchThrowCacheAndNetworkErrors() = runTest(before = { setUp() }, after = { tearDown() }) {
    assertFailsWith(CacheMissException::class) {
      apolloClient.query(EpisodeHeroNameQuery(Episode.EMPIRE))
          .fetchPolicy(FetchPolicy.CacheOnly)
          .watch(WatchErrorHandling.THROW_CACHE_AND_NETWORK_ERRORS)
          .first()
    }

    assertFailsWith(ApolloHttpException::class) {
      apolloClient.query(EpisodeHeroNameQuery(Episode.EMPIRE))
          .fetchPolicy(FetchPolicy.NetworkOnly)
          .watch(WatchErrorHandling.THROW_CACHE_AND_NETWORK_ERRORS)
          .first()
    }

    assertFailsWith(ApolloCompositeException::class) {
      apolloClient.query(EpisodeHeroNameQuery(Episode.EMPIRE))
          .fetchPolicy(FetchPolicy.NetworkFirst)
          .watch(WatchErrorHandling.THROW_CACHE_AND_NETWORK_ERRORS)
          .first()
    }

    assertFailsWith(ApolloCompositeException::class) {
      apolloClient.query(EpisodeHeroNameQuery(Episode.EMPIRE))
          .fetchPolicy(FetchPolicy.CacheFirst)
          .watch(WatchErrorHandling.THROW_CACHE_AND_NETWORK_ERRORS)
          .first()
    }
  }

  @Test
  fun refetchThrowCacheAndNetworkErrors() = runTest(before = { setUp() }, after = { tearDown() }) {
    val channel = Channel<EpisodeHeroNameQuery.Data?>()

    val query = EpisodeHeroNameQuery(Episode.EMPIRE)

    var throwable: Throwable? = null

    // The first query should get a "R2-D2" name
    val job = launch {
      mockServer.enqueue(testFixtureToUtf8("EpisodeHeroNameResponseWithId.json"))
      apolloClient.query(query)
          .fetchPolicy(FetchPolicy.NetworkOnly)
          .refetchPolicy(FetchPolicy.NetworkOnly)
          .watch(refetchErrorHandling = WatchErrorHandling.THROW_CACHE_AND_NETWORK_ERRORS)
          .catch { throwable = it }
          .collect {
            channel.send(it.data)
          }
    }
    assertEquals(channel.receiveOrTimeout()?.hero?.name, "R2-D2")

    // Another newer call gets updated information with "Artoo"
    // Due to .refetchPolicy(FetchPolicy.NetworkOnly), a subsequent call will be executed in watch()
    // we didn't enqueue anything in mockServer so a network exception is thrown, and surfaced due to THROW_CACHE_AND_NETWORK_ERRORS
    mockServer.enqueue(testFixtureToUtf8("EpisodeHeroNameResponseNameChange.json"))
    apolloClient.query(query).fetchPolicy(FetchPolicy.NetworkOnly).execute()

    channel.assertEmpty()

    assertIs<ApolloHttpException>(throwable)

    job.cancel()
  }
}
