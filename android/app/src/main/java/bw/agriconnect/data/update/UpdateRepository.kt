package bw.agriconnect.data.update

import bw.agriconnect.core.result.AppResult
import bw.agriconnect.core.result.runCatchingApi
import bw.agriconnect.data.remote.ApiService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps the public `GET /app/version` call and folds the manifest into an
 * [UpdateDecision] via the pure [decide] function.
 *
 * The endpoint is unauthenticated; a transport/HTTP/parse failure surfaces as
 * [AppResult.Failure] and is swallowed by [UpdateChecker] — a broken update
 * server must never interrupt, slow down, or lock out the app.
 */
@Singleton
class UpdateRepository @Inject constructor(
    private val api: ApiService,
) {
    /** Fetches the manifest and decides for [currentVersionCode]. */
    suspend fun check(currentVersionCode: Int): AppResult<UpdateDecision> =
        when (val result = runCatchingApi { api.getAppVersion() }) {
            is AppResult.Success -> AppResult.Success(decide(currentVersionCode, result.data))
            is AppResult.Failure -> result
        }
}
