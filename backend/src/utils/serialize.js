/**
 * Serialisation helpers. Database column names are snake_case; the public API is
 * camelCase. All money columns are NUMERIC and must be emitted as numbers with
 * exactly two decimals so the Android client can parse them deterministically.
 */

export function num(value, decimals = 2) {
  if (value === null || value === undefined) return null;
  const n = Number(value);
  return Number(n.toFixed(decimals));
}

export function intOrNull(value) {
  if (value === null || value === undefined) return null;
  const n = Number.parseInt(value, 10);
  return Number.isFinite(n) ? n : null;
}

export function iso(value) {
  if (!value) return null;
  return value instanceof Date ? value.toISOString() : new Date(value).toISOString();
}

export function dateOnly(value) {
  if (!value) return null;
  if (typeof value === 'string') return value.slice(0, 10);
  return new Date(value).toISOString().slice(0, 10);
}

/** Public user shape — never leaks password_hash or internal columns. */
export function toPublicUser(row) {
  if (!row) return null;
  return {
    userId: row.id,
    fullName: row.full_name,
    phoneNumber: row.phone_number,
    email: row.email ?? null,
    role: row.role,
    accountStatus: row.account_status,
    avatarUrl: row.avatar_url ?? null,
    notificationPreferences: row.notification_preferences ?? {
      orders: true,
      sync: true,
      satellite: true,
      marketplace: true,
    },
    verifiedAt: iso(row.verified_at),
    createdAt: iso(row.created_at),
    updatedAt: iso(row.updated_at),
  };
}

export function toPlot(row) {
  return {
    plotId: row.id,
    clientId: row.client_id ?? null,
    farmerId: row.farmer_id,
    plotName: row.plot_name,
    areaHectares: row.area_hectares === null ? null : num(row.area_hectares, 4),
    geoJsonBoundary: row.geojson_boundary ?? null,
    centroidLat: row.centroid_lat ?? null,
    centroidLng: row.centroid_lng ?? null,
    notes: row.notes ?? null,
    imageUrl: row.image_url ?? null,
    createdAt: iso(row.created_at),
    updatedAt: iso(row.updated_at),
  };
}

export function toHarvest(row) {
  return {
    harvestId: row.id,
    clientId: row.client_id ?? null,
    farmerId: row.farmer_id,
    plotId: row.plot_id ?? null,
    cropType: row.crop_type,
    quantityKg: num(row.quantity_kg),
    harvestDate: dateOnly(row.harvest_date),
    notes: row.notes ?? null,
    createdAt: iso(row.created_at),
    updatedAt: iso(row.updated_at),
  };
}

export function toLivestock(row) {
  return {
    countId: row.id,
    clientId: row.client_id ?? null,
    farmerId: row.farmer_id,
    animalType: row.animal_type,
    headCount: row.head_count,
    recordDate: dateOnly(row.record_date),
    notes: row.notes ?? null,
    createdAt: iso(row.created_at),
    updatedAt: iso(row.updated_at),
  };
}

export function toSale(row) {
  return {
    transactionId: row.id,
    clientId: row.client_id ?? null,
    farmerId: row.farmer_id,
    item: row.item,
    quantity: num(row.quantity),
    unitPrice: num(row.unit_price),
    amount: num(row.amount),
    currency: row.currency,
    buyerName: row.buyer_name ?? null,
    date: dateOnly(row.sale_date),
    notes: row.notes ?? null,
    createdAt: iso(row.created_at),
    updatedAt: iso(row.updated_at),
  };
}

export function toExpense(row) {
  return {
    expenseId: row.id,
    clientId: row.client_id ?? null,
    farmerId: row.farmer_id,
    category: row.category,
    description: row.description ?? null,
    amount: num(row.amount),
    currency: row.currency,
    expenseDate: dateOnly(row.expense_date),
    createdAt: iso(row.created_at),
    updatedAt: iso(row.updated_at),
  };
}

export function toNdvi(row) {
  return {
    ndviId: row.id,
    plotId: row.plot_id,
    farmerId: row.farmer_id,
    satelliteDate: dateOnly(row.satellite_date),
    meanNdviValue: row.mean_ndvi_value === null ? null : num(row.mean_ndvi_value, 4),
    stressLevel: row.stress_level ?? null,
    status: row.status,
    failureReason: row.failure_reason ?? null,
    cloudCover: row.cloud_cover === null ? null : num(row.cloud_cover, 2),
    compressedVectorJson: row.compressed_vector_json ?? null,
    isCached: row.is_cached,
    source: row.source,
    createdAt: iso(row.created_at),
  };
}

export function toListing(row) {
  return {
    listingId: row.id,
    farmerId: row.farmer_id,
    produce: row.produce,
    description: row.description ?? null,
    quantity: num(row.quantity),
    reservedQty: num(row.reserved_qty),
    soldQty: num(row.sold_qty),
    availableQty: num(Number(row.quantity) - Number(row.reserved_qty) - Number(row.sold_qty)),
    unit: row.unit,
    price: num(row.price),
    currency: row.currency,
    status: row.status,
    imageUrl: row.image_url ?? null,
    locationText: row.location_text ?? null,
    publishedAt: iso(row.published_at),
    createdAt: iso(row.created_at),
    updatedAt: iso(row.updated_at),
    ...(row.farmer_name ? { farmerName: row.farmer_name } : {}),
  };
}

export function toOrder(row) {
  return {
    orderId: row.id,
    listingId: row.listing_id,
    buyerId: row.buyer_id,
    farmerId: row.farmer_id,
    quantity: num(row.quantity),
    unitPrice: num(row.unit_price),
    totalPrice: num(row.total_price),
    currency: row.currency,
    status: row.status,
    note: row.note ?? null,
    buyerContact: row.buyer_contact ?? null,
    farmerDecisionAt: iso(row.farmer_decision_at),
    createdAt: iso(row.created_at),
    updatedAt: iso(row.updated_at),
    ...(row.produce ? { produce: row.produce } : {}),
    ...(row.buyer_name ? { buyerName: row.buyer_name } : {}),
  };
}

export function toNotification(row) {
  return {
    notificationId: row.id,
    userId: row.user_id,
    type: row.type,
    title: row.title,
    body: row.body,
    relatedEntityType: row.related_entity_type ?? null,
    relatedEntityId: row.related_entity_id ?? null,
    isRead: row.is_read,
    createdAt: iso(row.created_at),
  };
}

export function paginated(items, { page, pageSize, total }) {
  return {
    items,
    page,
    pageSize,
    total,
    hasMore: page * pageSize < total,
  };
}

export default {
  num, iso, dateOnly, toPublicUser, toPlot, toHarvest, toLivestock, toSale, toExpense,
  toNdvi, toListing, toOrder, toNotification, paginated,
};
