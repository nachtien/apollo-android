package com.apollographql.apollo3.interceptor.cache

import com.apollographql.apollo3.api.ApolloRequest
import com.apollographql.apollo3.api.ApolloResponse
import com.apollographql.apollo3.api.CustomScalarAdapters
import com.apollographql.apollo3.api.Operation
import com.apollographql.apollo3.api.Query
import com.apollographql.apollo3.api.exception.ApolloCompositeException
import com.apollographql.apollo3.cache.CacheHeaders
import com.apollographql.apollo3.cache.normalized.ApolloStore
import com.apollographql.apollo3.cache.normalized.Platform
import com.apollographql.apollo3.cache.normalized.internal.dependentKeys
import com.apollographql.apollo3.interceptor.ApolloInterceptorChain
import com.apollographql.apollo3.interceptor.ApolloRequestInterceptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.single

class ApolloCacheInterceptor(private val store: ApolloStore) : ApolloRequestInterceptor {

  override fun <D : Operation.Data> intercept(request: ApolloRequest<D>, chain: ApolloInterceptorChain): Flow<ApolloResponse<D>> {
    val defaultFetchPolicy = if (request.operation is Query) FetchPolicy.CacheFirst else FetchPolicy.NetworkOnly
    val fetchPolicy = request.executionContext[FetchPolicyContext]?.fetchPolicy ?: defaultFetchPolicy
    val refetchPolicy = request.executionContext[RefetchPolicyContext]?.refetchPolicy
    val optimisticUpdates = request.executionContext[OptimisticUpdates]?.data
    val responseAdapterCache = request.executionContext[CustomScalarAdapters]!!

    return flow {
      var result = kotlin.runCatching {
        @Suppress("UNCHECKED_CAST")
        fetchOne(request, chain, fetchPolicy, optimisticUpdates as D?)
      }
      val response = result.getOrNull()

      if (response != null) {
        emit(response)
      }

      if (refetchPolicy == null) {
        if (result.isFailure) {
          throw result.exceptionOrNull()!!
        }
        return@flow
      }

      var watchedKeys = if (response != null && !response.hasErrors() && response.data != null) {
        store.normalize(request.operation, response.data!!, responseAdapterCache).values.dependentKeys()
      } else {
        null
      }

      store.changedKeys.collect { changedKeys ->
        if (watchedKeys == null || changedKeys.intersect(watchedKeys!!).isNotEmpty()) {
          result = kotlin.runCatching {
            fetchOne(request, chain, refetchPolicy, null)
          }

          val newResponse = result.getOrNull()
          if (newResponse != null) {
            emit(newResponse)

            if (!newResponse.hasErrors() && newResponse.data != null) {
              watchedKeys = store.normalize(request.operation, newResponse.data!!, responseAdapterCache).values.dependentKeys()
            }
          }
        }
      }
    }
  }

  private suspend fun <D : Operation.Data> fetchOne(
      request: ApolloRequest<D>,
      chain: ApolloInterceptorChain,
      fetchPolicy: FetchPolicy,
      optimisticUpdates: D?,
  ): ApolloResponse<D> {
    val responseAdapterCache = request.executionContext[CustomScalarAdapters]!!

    if (optimisticUpdates != null) {
      store.writeOptimisticUpdates(
          operation = request.operation,
          operationData = optimisticUpdates,
          mutationId = request.requestUuid,
          customScalarAdapters = responseAdapterCache,
          publish = true
      )
    }

    val result = kotlin.runCatching {
      fetchOneMightThrow(request, chain, fetchPolicy)
    }

    if (result.isSuccess) {
      val response = result.getOrThrow()

      val optimisticKeys = if (optimisticUpdates != null) {
        store.rollbackOptimisticUpdates(request.requestUuid, publish = false)
      } else {
        emptySet()
      }
      val cacheKeys = if (!response.isFromCache && response.data != null) {
        store.writeOperation(request.operation, response.data!!, responseAdapterCache, CacheHeaders.NONE, publish = false)
      } else {
        emptySet()
      }
      store.publish(optimisticKeys + cacheKeys)

    }

    return result.getOrThrow()
  }

  private suspend fun <D : Operation.Data> fetchOneMightThrow(
      request: ApolloRequest<D>,
      chain: ApolloInterceptorChain,
      fetchPolicy: FetchPolicy,
  ): ApolloResponse<D> {
    val responseAdapterCache = request.executionContext[CustomScalarAdapters]!!

    when (fetchPolicy) {
      FetchPolicy.CacheFirst -> {
        Platform.ensureNeverFrozen(store)
        val cacheResult = kotlin.runCatching {
          readFromCache(request, responseAdapterCache)
        }

        val cacheResponse = cacheResult.getOrNull()
        if (cacheResponse != null) {
          return cacheResponse
        }

        val networkResult = kotlin.runCatching {
          proceed(request, chain).single()
        }

        val networkResponse = networkResult.getOrNull()
        if (networkResponse != null) {
          return networkResponse
        }

        throw ApolloCompositeException(
            cacheResult.exceptionOrNull(),
            networkResult.exceptionOrNull()
        )
      }
      FetchPolicy.NetworkFirst -> {
        val networkResult = kotlin.runCatching {
          proceed(request, chain).single()
        }

        val networkResponse = networkResult.getOrNull()
        if (networkResponse != null) {
          return networkResponse
        }

        val cacheResult = kotlin.runCatching {
          readFromCache(request, responseAdapterCache)
        }

        val cacheResponse = cacheResult.getOrNull()
        if (cacheResponse != null) {
          return cacheResponse
        }

        throw ApolloCompositeException(
            networkResult.exceptionOrNull(),
            cacheResult.exceptionOrNull(),
        )
      }
      FetchPolicy.CacheOnly -> {
        return readFromCache(request, responseAdapterCache)
      }
      FetchPolicy.NetworkOnly -> {
        return proceed(request, chain).single()
      }
    }
  }

  private fun <D : Operation.Data> proceed(request: ApolloRequest<D>, chain: ApolloInterceptorChain): Flow<ApolloResponse<D>> {
    return chain.proceed(request).map {
      it.setFromCache(false)
    }
  }

  private fun <D : Operation.Data> ApolloResponse<D>.setFromCache(fromCache: Boolean): ApolloResponse<D> {
    return copy(executionContext = executionContext + CacheOutput(fromCache))
  }

  private suspend fun <D : Operation.Data> readFromCache(
      request: ApolloRequest<D>,
      customScalarAdapters: CustomScalarAdapters,
  ): ApolloResponse<D> {
    val operation = request.operation

    val data = store.readOperation(
        operation,
        customScalarAdapters
    )

    return ApolloResponse(
        requestUuid = request.requestUuid,
        operation = operation,
        data = data,
        executionContext = request.executionContext + CacheOutput(true)
    )
  }
}