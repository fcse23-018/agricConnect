package bw.agriconnect.core.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reads the device's current position for GPS-assisted plot boundary mapping.
 *
 * The manifest already declares `ACCESS_FINE_LOCATION` for exactly this purpose, and the
 * user may refuse it — every caller must therefore handle `null` and offer manual corner
 * entry instead (see `BoundaryEditor`).
 *
 * A fresh fix is preferred, but `getCurrentLocation` can hang indefinitely with no sky
 * view, so it is raced against a short timeout and then falls back to the last known
 * position. That keeps "capture corner" responsive in the field rather than spinning.
 */
object CurrentLocation {

    /** The permission a caller must request before [capture] can return a fix. */
    const val FINE_PERMISSION = Manifest.permission.ACCESS_FINE_LOCATION

    private const val FRESH_FIX_TIMEOUT_MS = 12_000L

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, FINE_PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * The best position available now, or `null` when the permission is missing or no fix
     * can be obtained. Never throws — a failure is a normal, expected outcome.
     */
    suspend fun capture(context: Context): GeoVertex? {
        if (!hasPermission(context)) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        return withTimeoutOrNull(FRESH_FIX_TIMEOUT_MS) { awaitCurrent(client) } ?: awaitLastKnown(client)
    }

    @SuppressLint("MissingPermission") // Guarded by hasPermission() in capture().
    private suspend fun awaitCurrent(client: FusedLocationProviderClient): GeoVertex? =
        suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationTokenSource()
            continuation.invokeOnCancellation { cancellation.cancel() }
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
                .addOnSuccessListener { location ->
                    if (continuation.isActive) continuation.resume(location?.toVertex())
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(null)
                }
        }

    @SuppressLint("MissingPermission") // Guarded by hasPermission() in capture().
    private suspend fun awaitLastKnown(client: FusedLocationProviderClient): GeoVertex? =
        suspendCancellableCoroutine { continuation ->
            client.lastLocation
                .addOnSuccessListener { location ->
                    if (continuation.isActive) continuation.resume(location?.toVertex())
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(null)
                }
        }

    private fun android.location.Location.toVertex(): GeoVertex = GeoVertex(latitude, longitude)
}
