package bw.agriconnect.data.remote

import bw.agriconnect.data.remote.dto.AuthResponse
import bw.agriconnect.data.remote.dto.ChangePasswordRequest
import bw.agriconnect.data.remote.dto.CodeDispatchResponse
import bw.agriconnect.data.remote.dto.CreateExpenseRequest
import bw.agriconnect.data.remote.dto.CreateFarmPlotRequest
import bw.agriconnect.data.remote.dto.CreateHarvestRequest
import bw.agriconnect.data.remote.dto.CreateListingRequest
import bw.agriconnect.data.remote.dto.CreateLivestockRequest
import bw.agriconnect.data.remote.dto.CreateOrderRequest
import bw.agriconnect.data.remote.dto.CreateSaleRequest
import bw.agriconnect.data.remote.dto.DeletionRequestBody
import bw.agriconnect.data.remote.dto.DeletionRequestDto
import bw.agriconnect.data.remote.dto.DeletionRequestResponse
import bw.agriconnect.data.remote.dto.DeviceTokenRequest
import bw.agriconnect.data.remote.dto.ExpenseDto
import bw.agriconnect.data.remote.dto.UpdateExpenseRequest
import bw.agriconnect.data.remote.dto.FarmPlotDto
import bw.agriconnect.data.remote.dto.FinanceSummaryDto
import bw.agriconnect.data.remote.dto.ForgotPasswordRequest
import bw.agriconnect.data.remote.dto.HarvestDto
import bw.agriconnect.data.remote.dto.HealthResponse
import bw.agriconnect.data.remote.dto.IdentifierRequest
import bw.agriconnect.data.remote.dto.ItemsResponse
import bw.agriconnect.data.remote.dto.ListingDto
import bw.agriconnect.data.remote.dto.LivestockDto
import bw.agriconnect.data.remote.dto.LoginRequest
import bw.agriconnect.data.remote.dto.LogoutRequest
import bw.agriconnect.data.remote.dto.MeResponse
import bw.agriconnect.data.remote.dto.MessageResponse
import bw.agriconnect.data.remote.dto.NdviDto
import bw.agriconnect.data.remote.dto.NdviRequest
import bw.agriconnect.data.remote.dto.NdviResponse
import bw.agriconnect.data.remote.dto.NotificationDto
import bw.agriconnect.data.remote.dto.NotificationPreferencesDto
import bw.agriconnect.data.remote.dto.NotificationResponse
import bw.agriconnect.data.remote.dto.OrderDto
import bw.agriconnect.data.remote.dto.PageDto
import bw.agriconnect.data.remote.dto.PublicDeletionRequest
import bw.agriconnect.data.remote.dto.RefreshRequest
import bw.agriconnect.data.remote.dto.RegisterRequest
import bw.agriconnect.data.remote.dto.ResetPasswordRequest
import bw.agriconnect.data.remote.dto.ResetTokenResponse
import bw.agriconnect.data.remote.dto.SaleDto
import bw.agriconnect.data.remote.dto.SyncPushRequest
import bw.agriconnect.data.remote.dto.SyncPushResponse
import bw.agriconnect.data.remote.dto.SyncPullResponse
import bw.agriconnect.data.remote.dto.TokenPairResponse
import bw.agriconnect.data.remote.dto.UpdateFarmPlotRequest
import bw.agriconnect.data.remote.dto.UpdateHarvestRequest
import bw.agriconnect.data.remote.dto.UpdateListingRequest
import bw.agriconnect.data.remote.dto.UpdateLivestockRequest
import bw.agriconnect.data.remote.dto.UpdateProfileRequest
import bw.agriconnect.data.remote.dto.UpdateSaleRequest
import bw.agriconnect.data.remote.dto.UpdatedCountResponse
import bw.agriconnect.data.remote.dto.UploadResponse
import bw.agriconnect.data.remote.dto.VerifyCodeRequest
import bw.agriconnect.data.remote.dto.EmployerLinkRequest
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The complete AgriConnect v1 REST surface (docs/02-API-CONTRACT.md §2.1–§2.10).
 *
 * Design notes:
 *  - This is the *only* Retrofit interface. Endpoints are grouped by module with
 *    comment banners rather than split into many services, so a base URL change or
 *    a global header change stays in one place.
 *  - Mutating endpoints that the contract marks idempotent (`/auth/register`,
 *    `/orders`, `/sync/push`) accept a nullable `Idempotency-Key`; a null value is
 *    simply omitted so the server falls back to its endpoint-level de-duplication.
 *  - `204 No Content` endpoints return `Response<Unit>` so the empty body is never
 *    passed through a JSON parser.
 *  - The silent-refresh call is intentionally *not* here (it must not recurse
 *    through the authenticated stack); see
 *    [bw.agriconnect.data.remote.TokenAuthenticator].
 */
interface ApiService {

    /* ---------------------------------- auth ------------------------------- */

    @POST("auth/register")
    suspend fun register(
        @Header("Idempotency-Key") idempotencyKey: String? = null,
        @Body body: RegisterRequest,
    ): AuthResponse

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): TokenPairResponse

    @POST("auth/logout")
    suspend fun logout(@Body body: LogoutRequest): Response<Unit>

    @POST("auth/logout-all")
    suspend fun logoutAll(): Response<Unit>

    @GET("auth/me")
    suspend fun me(): MeResponse

    @POST("auth/password/forgot")
    suspend fun passwordForgot(@Body body: ForgotPasswordRequest): CodeDispatchResponse

    @POST("auth/password/verify")
    suspend fun passwordVerify(@Body body: VerifyCodeRequest): ResetTokenResponse

    @POST("auth/password/reset")
    suspend fun passwordReset(@Body body: ResetPasswordRequest): MessageResponse

    @POST("auth/verification/request")
    suspend fun verificationRequest(@Body body: IdentifierRequest): CodeDispatchResponse

    @POST("auth/verification/confirm")
    suspend fun verificationConfirm(@Body body: VerifyCodeRequest): MessageResponse

    /* --------------------------------- users ------------------------------- */

    @GET("users/me")
    suspend fun getMe(): MeResponse

    @PATCH("users/me")
    suspend fun patchMe(@Body body: UpdateProfileRequest): MeResponse

    @POST("users/me/password")
    suspend fun changePassword(@Body body: ChangePasswordRequest): MessageResponse

    @PUT("users/me/notification-preferences")
    suspend fun putNotificationPreferences(
        @Body body: NotificationPreferencesDto,
    ): MeResponse

    /** Multipart image upload (≤ 2 MB, jpeg/png/webp). */
    @Multipart
    @POST("users/me/avatar")
    suspend fun uploadAvatar(@Part avatar: MultipartBody.Part): MeResponse

    @GET("users/me/export")
    suspend fun export(): JsonObject

    /**
     * Generic image upload (≤ 2 MB, jpeg/png/webp): stores one image and returns its
     * origin-relative URL path under `{ url }`. The caller attaches the returned path to
     * the listing/plot it is editing. Distinct from [uploadAvatar], which attaches the
     * image to the caller's own profile in the same request.
     */
    @Multipart
    @POST("uploads")
    suspend fun uploadImage(@Part image: MultipartBody.Part): UploadResponse

    /** Link a FARMER to the FARM_OWNER who employs them (migration 005). */
    @POST("users/me/employer-link")
    suspend fun employerLink(@Body body: EmployerLinkRequest): MeResponse

    /* ------------------------- account deletion (§2.3) --------------------- */

    /** `POST` returns the newly-created request as a bare object. */
    @POST("users/me/deletion-request")
    suspend fun createDeletionRequest(@Body body: DeletionRequestBody): DeletionRequestDto

    /** `GET` nests the (possibly absent) request under `request`. */
    @GET("users/me/deletion-request")
    suspend fun getDeletionRequest(): DeletionRequestResponse

    @POST("users/me/deletion-request/cancel")
    suspend fun cancelDeletionRequest(): DeletionRequestResponse

    /** Store-required public deletion page; always `202` and generic. */
    @POST("public/account-deletion")
    suspend fun publicDeletionRequest(@Body body: PublicDeletionRequest): MessageResponse

    /* --------------------------------- farms ------------------------------- */

    @GET("farms/plots")
    suspend fun listPlots(): ItemsResponse<FarmPlotDto>

    @POST("farms/plots")
    suspend fun createPlot(@Body body: CreateFarmPlotRequest): FarmPlotDto

    @GET("farms/plots/{id}")
    suspend fun getPlot(@Path("id") id: String): FarmPlotDto

    @PATCH("farms/plots/{id}")
    suspend fun patchPlot(@Path("id") id: String, @Body body: UpdateFarmPlotRequest): FarmPlotDto

    @DELETE("farms/plots/{id}")
    suspend fun deletePlot(@Path("id") id: String): Response<Unit>

    /* ------------------------------ farm records --------------------------- */

    @GET("farm/harvests")
    suspend fun listHarvests(
        @Query("page") page: Int? = null,
        @Query("pageSize") pageSize: Int? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("cropType") cropType: String? = null,
    ): PageDto<HarvestDto>

    @POST("farm/harvests")
    suspend fun createHarvest(@Body body: CreateHarvestRequest): HarvestDto

    @PATCH("farm/harvests/{id}")
    suspend fun patchHarvest(@Path("id") id: String, @Body body: UpdateHarvestRequest): HarvestDto

    @DELETE("farm/harvests/{id}")
    suspend fun deleteHarvest(@Path("id") id: String): Response<Unit>

    @GET("farm/livestock")
    suspend fun listLivestock(
        @Query("page") page: Int? = null,
        @Query("pageSize") pageSize: Int? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
    ): PageDto<LivestockDto>

    @POST("farm/livestock")
    suspend fun createLivestock(@Body body: CreateLivestockRequest): LivestockDto

    @PATCH("farm/livestock/{id}")
    suspend fun patchLivestock(
        @Path("id") id: String,
        @Body body: UpdateLivestockRequest,
    ): LivestockDto

    @DELETE("farm/livestock/{id}")
    suspend fun deleteLivestock(@Path("id") id: String): Response<Unit>

    /* --------------------------------- finance ----------------------------- */

    @GET("finance/sales")
    suspend fun listSales(
        @Query("page") page: Int? = null,
        @Query("pageSize") pageSize: Int? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
    ): PageDto<SaleDto>

    @POST("finance/sales")
    suspend fun createSale(@Body body: CreateSaleRequest): SaleDto

    @PATCH("finance/sales/{id}")
    suspend fun patchSale(@Path("id") id: String, @Body body: UpdateSaleRequest): SaleDto

    @DELETE("finance/sales/{id}")
    suspend fun deleteSale(@Path("id") id: String): Response<Unit>

    @GET("finance/expenses")
    suspend fun listExpenses(
        @Query("page") page: Int? = null,
        @Query("pageSize") pageSize: Int? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
    ): PageDto<ExpenseDto>

    @POST("finance/expenses")
    suspend fun createExpense(@Body body: CreateExpenseRequest): ExpenseDto

    @PATCH("finance/expenses/{id}")
    suspend fun patchExpense(@Path("id") id: String, @Body body: UpdateExpenseRequest): ExpenseDto

    @DELETE("finance/expenses/{id}")
    suspend fun deleteExpense(@Path("id") id: String): Response<Unit>

    @GET("finance/summary")
    suspend fun financeSummary(
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
    ): FinanceSummaryDto

    /* -------------------------------- satellite ---------------------------- */

    @POST("satellite/ndvi")
    suspend fun computeNdvi(@Body body: NdviRequest): NdviDto

    @GET("satellite/ndvi")
    suspend fun listNdvi(
        @Query("plotId") plotId: String,
        @Query("page") page: Int? = null,
        @Query("pageSize") pageSize: Int? = null,
    ): PageDto<NdviDto>

    @GET("satellite/ndvi/latest")
    suspend fun latestNdvi(@Query("plotId") plotId: String): NdviResponse

    @GET("satellite/ndvi/{id}")
    suspend fun getNdvi(@Path("id") id: String): NdviDto

    /* ------------------------------- marketplace --------------------------- */

    @GET("marketplace/listings")
    suspend fun listListings(
        @Query("search") search: String? = null,
        @Query("crop") crop: String? = null,
        @Query("minPrice") minPrice: Double? = null,
        @Query("maxPrice") maxPrice: Double? = null,
        @Query("sort") sort: String? = null,
        @Query("page") page: Int? = null,
        @Query("pageSize") pageSize: Int? = null,
    ): PageDto<ListingDto>

    @GET("marketplace/listings/{id}")
    suspend fun getListing(@Path("id") id: String): ListingDto

    @GET("marketplace/my-listings")
    suspend fun myListings(
        @Query("page") page: Int? = null,
        @Query("pageSize") pageSize: Int? = null,
    ): PageDto<ListingDto>

    @POST("marketplace/listings")
    suspend fun createListing(@Body body: CreateListingRequest): ListingDto

    @PATCH("marketplace/listings/{id}")
    suspend fun patchListing(
        @Path("id") id: String,
        @Body body: UpdateListingRequest,
    ): ListingDto

    @POST("marketplace/listings/{id}/publish")
    suspend fun publishListing(@Path("id") id: String): ListingDto

    @POST("marketplace/listings/{id}/archive")
    suspend fun archiveListing(@Path("id") id: String): ListingDto

    /* --------------------------------- orders ------------------------------ */

    @POST("orders")
    suspend fun createOrder(
        @Header("Idempotency-Key") idempotencyKey: String? = null,
        @Body body: CreateOrderRequest,
    ): OrderDto

    @GET("orders")
    suspend fun listOrders(
        @Query("page") page: Int? = null,
        @Query("pageSize") pageSize: Int? = null,
    ): PageDto<OrderDto>

    @GET("orders/{id}")
    suspend fun getOrder(@Path("id") id: String): OrderDto

    @POST("orders/{id}/confirm")
    suspend fun confirmOrder(@Path("id") id: String): OrderDto

    @POST("orders/{id}/reject")
    suspend fun rejectOrder(@Path("id") id: String): OrderDto

    @POST("orders/{id}/cancel")
    suspend fun cancelOrder(@Path("id") id: String): OrderDto

    @POST("orders/{id}/complete")
    suspend fun completeOrder(@Path("id") id: String): OrderDto

    /* ------------------------------ notifications -------------------------- */

    @GET("notifications")
    suspend fun listNotifications(
        @Query("unreadOnly") unreadOnly: Boolean? = null,
        @Query("page") page: Int? = null,
        @Query("pageSize") pageSize: Int? = null,
    ): PageDto<NotificationDto>

    @PATCH("notifications/{id}/read")
    suspend fun markRead(@Path("id") id: String): NotificationResponse

    @POST("notifications/read-all")
    suspend fun markAllRead(): UpdatedCountResponse

    /**
     * Registers the FCM token. The server responds `204 No Content`, so the empty
     * body is modelled as `Response<Unit>` and never run through the JSON parser.
     */
    @POST("notifications/devices")
    suspend fun registerDevice(@Body body: DeviceTokenRequest): Response<Unit>

    /* --------------------------------- sync -------------------------------- */

    @POST("sync/push")
    suspend fun syncPush(
        @Header("Idempotency-Key") idempotencyKey: String? = null,
        @Body body: SyncPushRequest,
    ): SyncPushResponse

    @GET("sync/pull")
    suspend fun syncPull(@Query("since") since: String? = null): SyncPullResponse

    /* -------------------------------- health ------------------------------- */

    /**
     * The health probe lives at the host root, not under `/api/v1`. A leading `/`
     * in the annotation resolves it against the base URL's origin, so the request
     * targets `https://host/health` regardless of the versioned base path.
     */
    @GET("/health")
    suspend fun health(): HealthResponse
}
