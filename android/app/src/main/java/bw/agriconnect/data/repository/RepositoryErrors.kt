package bw.agriconnect.data.repository

import bw.agriconnect.core.result.AppError

/**
 * Shared validation helpers for repository implementations.
 *
 * Why a small helper: validation returns an [AppError?] (not [AppResult]) so the
 * call site can choose the appropriate [AppResult.Failure] type without fighting
 * covariance between `AppResult<Unit>` and `AppResult<T>`. Keeping them here,
 * outside [RepositorySupport], avoids touching the existing shared helpers.
 */

internal fun validationError(
    message: String,
    fieldErrors: Map<String, String> = emptyMap(),
    httpStatus: Int? = 400,
): AppError = AppError(
    code = AppError.VALIDATION_ERROR,
    message = message,
    fieldErrors = fieldErrors,
    httpStatus = httpStatus,
)

internal fun notFoundError(
    message: String = "We couldn't find what you were looking for.",
): AppError = AppError(
    code = AppError.NOT_FOUND,
    message = message,
    httpStatus = 404,
)
