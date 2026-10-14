/**
 * Development seed data for AgriConnect.
 *
 *   npm run seed
 *
 * Creates one farmer, one farm employee (linked to that farmer), one buyer, and a
 * realistic slice of farm/marketplace/satellite data so every screen has
 * something to show.
 *
 * It is **idempotent**: the demo accounts are removed (cascading their data)
 * before being re-created, so running it repeatedly always converges to the same
 * state. It refuses to run against a production database.
 *
 * Credentials are demo-only and printed at the end. Never run this against
 * anything but a development database.
 */
import { config } from '../config/index.js';
import { pool, withTransaction } from './index.js';
import { hashPassword } from '../utils/password.js';
import { classifyNdvi } from '../services/ndvi.js';

if (config.isProd) {
  // eslint-disable-next-line no-console
  console.error('Refusing to seed a production database (NODE_ENV=production).');
  process.exit(1);
}

/** Demo accounts. Phones satisfy the `^\+[1-9][0-9]{7,14}$` CHECK. */
const DEMO = {
  farmer: { name: 'Thabo Mokoena', phone: '+26771100001', email: 'thabo@example.bw', password: 'farmer2026', role: 'FARMER' },
  employee: { name: 'Neo Kgosi', phone: '+26771100002', email: null, password: 'worker2026', role: 'FARM_EMPLOYEE' },
  buyer: { name: 'Kagiso Trading', phone: '+26771100003', email: 'kagiso@example.bw', password: 'buyer2026', role: 'BUYER' },
};

const PHONES = [DEMO.farmer.phone, DEMO.employee.phone, DEMO.buyer.phone];

function daysAgo(n) {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return d.toISOString().slice(0, 10); // yyyy-mm-dd for `date` columns
}

async function removeDemoData(client) {
  // Order matters: orders reference listings with ON DELETE RESTRICT, so the
  // buyer (and therefore their orders) must go first.
  await client.query('DELETE FROM users WHERE phone_number = ANY($1::text[])', [PHONES]);
}

async function insertUser(client, { name, phone, email, password, role }, employerFarmerId = null) {
  const hash = await hashPassword(password);
  const { rows } = await client.query(
    `INSERT INTO users (full_name, phone_number, email, password_hash, role, account_status, employer_farmer_id, verified_at)
     VALUES ($1, $2, $3, $4, $5, 'VERIFIED', $6, now())
     RETURNING id`,
    [name, phone, email, hash, role, employerFarmerId],
  );
  return rows[0].id;
}

async function main() {
  // eslint-disable-next-line no-console
  const log = (...a) => console.log('[seed]', ...a);

  await withTransaction(async (client) => {
    log('removing any previous demo accounts…');
    await removeDemoData(client);

    log('creating users…');
    const farmerId = await insertUser(client, DEMO.farmer);
    const employeeId = await insertUser(client, DEMO.employee, farmerId);
    const buyerId = await insertUser(client, DEMO.buyer);

    // --- Farm plots -------------------------------------------------------
    const plots = [
      {
        name: 'North Field',
        area: 12.5,
        lat: -24.6282,
        lng: 25.9231,
        ring: [[25.9180, -24.6330], [25.9282, -24.6330], [25.9282, -24.6234], [25.9180, -24.6234], [25.9180, -24.6330]],
      },
      {
        name: 'River Plot',
        area: 6.2,
        lat: -24.6510,
        lng: 25.9010,
        ring: [[25.8980, -24.6540], [25.9040, -24.6540], [25.9040, -24.6480], [25.8980, -24.6480], [25.8980, -24.6540]],
      },
      {
        name: 'Cattle Paddock',
        area: 40.0,
        lat: -24.6100,
        lng: 25.9700,
        ring: [[25.9600, -24.6200], [25.9800, -24.6200], [25.9800, -24.6000], [25.9600, -24.6000], [25.9600, -24.6200]],
      },
    ];

    const plotIds = [];
    for (const p of plots) {
      const geo = { type: 'Polygon', coordinates: [p.ring] };
      const { rows } = await client.query(
        `INSERT INTO farm_plots (farmer_id, client_id, plot_name, area_hectares, geojson_boundary, centroid_lat, centroid_lng, notes)
         VALUES ($1, $2, $3, $4, $5::jsonb, $6, $7, $8)
         RETURNING id`,
        [farmerId, `seed-plot-${p.name.toLowerCase().replace(/\s+/g, '-')}`, p.name, p.area, JSON.stringify(geo), p.lat, p.lng, null],
      );
      plotIds.push(rows[0].id);
    }

    // --- Harvests (some attributed to the employee) ------------------------
    const harvests = [
      { crop: 'Maize', kg: 1850, days: 12, plot: 0, by: farmerId },
      { crop: 'Maize', kg: 940, days: 40, plot: 0, by: employeeId },
      { crop: 'Sorghum', kg: 620, days: 20, plot: 1, by: employeeId },
      { crop: 'Groundnuts', kg: 310, days: 55, plot: 1, by: farmerId },
    ];
    for (const h of harvests) {
      await client.query(
        `INSERT INTO harvest_records (farmer_id, plot_id, crop_type, quantity_kg, harvest_date, notes, client_id, created_by)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`,
        [farmerId, plotIds[h.plot], h.crop, h.kg, daysAgo(h.days), null, `seed-harvest-${h.crop}-${h.days}`, h.by],
      );
    }

    // --- Livestock --------------------------------------------------------
    const livestock = [
      { animal: 'Cattle', heads: 48, days: 5 },
      { animal: 'Goats', heads: 120, days: 5 },
      { animal: 'Chickens', heads: 310, days: 18 },
    ];
    for (const l of livestock) {
      await client.query(
        `INSERT INTO livestock_counts (farmer_id, animal_type, head_count, record_date, notes, client_id, created_by)
         VALUES ($1, $2, $3, $4, $5, $6, $7)`,
        [farmerId, l.animal, l.heads, daysAgo(l.days), null, `seed-livestock-${l.animal}`, employeeId],
      );
    }

    // --- Finance ----------------------------------------------------------
    const sales = [
      { item: 'Maize (50kg bags)', qty: 40, price: 180, days: 10, buyer: 'Local Co-op' },
      { item: 'Goats', qty: 6, price: 1200, days: 25, buyer: 'Gaborone Market' },
      { item: 'Maize (50kg bags)', qty: 22, price: 185, days: 3, buyer: 'Local Co-op' },
      { item: 'Groundnuts (kg)', qty: 150, price: 45, days: 45, buyer: 'Street vendor' },
    ];
    for (const s of sales) {
      await client.query(
        `INSERT INTO sale_transactions (farmer_id, item, quantity, unit_price, amount, currency, buyer_name, sale_date, notes, client_id)
         VALUES ($1, $2, $3, $4, $5, 'BWP', $6, $7, $8, $9)`,
        [farmerId, s.item, s.qty, s.price, s.qty * s.price, s.buyer, daysAgo(s.days), null, `seed-sale-${s.item}-${s.days}`],
      );
    }
    const expenses = [
      { category: 'Seed', description: 'Hybrid maize seed', amount: 2400, days: 60 },
      { category: 'Fertiliser', description: 'NPK 2:3:2', amount: 5100, days: 52 },
      { category: 'Fuel', description: 'Diesel for tractor', amount: 1750, days: 15 },
      { category: 'Veterinary', description: 'Cattle vaccination', amount: 900, days: 8 },
      { category: 'Labour', description: 'Harvest casual labour', amount: 3200, days: 12 },
    ];
    for (const e of expenses) {
      await client.query(
        `INSERT INTO expenses (farmer_id, category, description, amount, currency, expense_date, client_id)
         VALUES ($1, $2, $3, $4, 'BWP', $5, $6)`,
        [farmerId, e.category, e.description, e.amount, daysAgo(e.days), `seed-expense-${e.category}`],
      );
    }

    // --- Marketplace listings --------------------------------------------
    const listings = [
      { produce: 'Fresh Maize', qty: 500, unit: 'kg', price: 4.5, status: 'PUBLISHED', desc: 'Recently harvested white maize, sorted and bagged.', location: 'Kgatleng' },
      { produce: 'Goats', qty: 20, unit: 'head', price: 1250, status: 'PUBLISHED', desc: 'Healthy Boer cross goats, 18–24 months.', location: 'Kgatleng' },
      { produce: 'Groundnuts', qty: 300, unit: 'kg', price: 42, status: 'PUBLISHED', desc: 'Shelled groundnuts, 2026 harvest.', location: 'Kgatleng' },
      { produce: 'Sorghum', qty: 400, unit: 'kg', price: 6.2, status: 'DRAFT', desc: 'Draft listing — not yet published.', location: 'Kgatleng' },
    ];
    const listingIds = [];
    for (const l of listings) {
      const publishedAt = l.status === 'PUBLISHED' ? 'now()' : 'NULL';
      const { rows } = await client.query(
        `INSERT INTO produce_listings (farmer_id, produce, description, quantity, reserved_qty, sold_qty, unit, price, currency, status, location_text, published_at)
         VALUES ($1, $2, $3, $4, 0, 0, $5, $6, 'BWP', $7, $8, ${publishedAt})
         RETURNING id`,
        [farmerId, l.produce, l.desc, l.qty, l.unit, l.price, l.status, l.location],
      );
      listingIds.push(rows[0].id);
    }

    // --- Orders (buyer → farmer) -----------------------------------------
    // One PENDING (awaiting farmer confirmation) and one COMPLETED.
    const orders = [
      { listing: 0, qty: 50, status: 'PENDING', days: 2 },
      { listing: 2, qty: 25, status: 'COMPLETED', days: 20 },
    ];
    for (const o of orders) {
      const listingIdx = o.listing;
      const unitPrice = listings[listingIdx].price;
      await client.query(
        `INSERT INTO orders (listing_id, buyer_id, farmer_id, quantity, unit_price, total_price, currency, status, note, buyer_contact, farmer_decision_at, created_at)
         VALUES ($1, $2, $3, $4, $5, $6, 'BWP', $7, $8, $9, $10, now() - ($11 || ' days')::interval)`,
        [
          listingIds[listingIdx],
          buyerId,
          farmerId,
          o.qty,
          unitPrice,
          o.qty * unitPrice,
          o.status,
          o.status === 'PENDING' ? 'Please deliver to Gaborone if possible.' : null,
          '+26771100003',
          o.status === 'COMPLETED' ? daysAgo(19) : null,
          String(o.days),
        ],
      );
    }
    // Reflect the completed sale in the listing's stock counters.
    await client.query(
      `UPDATE produce_listings SET sold_qty = 25 WHERE id = $1`,
      [listingIds[2]],
    );

    // --- Notification feed ------------------------------------------------
    const notifications = [
      { type: 'ORDER_PLACED', title: 'New order received', body: 'Kagiso Trading ordered 50 kg of Fresh Maize.', read: false, user: farmerId },
      { type: 'NDVI_READY', title: 'Satellite analysis ready', body: 'A new vegetation reading is available for North Field.', read: false, user: farmerId },
      { type: 'ORDER_CONFIRMED', title: 'Order confirmed', body: 'Your order from Thabo Mokoena was confirmed.', read: true, user: buyerId },
    ];
    for (const n of notifications) {
      await client.query(
        `INSERT INTO notifications (user_id, type, title, body, is_read) VALUES ($1, $2, $3, $4, $5)`,
        [n.user, n.type, n.title, n.body, n.read],
      );
    }

    // --- Satellite NDVI ---------------------------------------------------
    // One healthy reading and one that failed on cloud cover, so both states
    // are visible in the UI without waiting for real imagery.
    const ndviRows = [
      { plot: 0, mean: 0.61, cloud: 12.5, status: 'GENERATED', days: 4 },
      { plot: 1, mean: 0.28, cloud: 22.0, status: 'GENERATED', days: 9 },
      { plot: 2, mean: null, cloud: 92.0, status: 'FAILED', days: 2, failure: 'Cloud cover above 80% — imagery unusable for this observation.' },
    ];
    for (const r of ndviRows) {
      await client.query(
        `INSERT INTO satellite_ndvi (plot_id, farmer_id, satellite_date, mean_ndvi_value, stress_level, status, failure_reason, cloud_cover, compressed_vector_json, is_cached, source)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::jsonb, false, 'MOCK')`,
        [
          plotIds[r.plot],
          farmerId,
          daysAgo(r.days),
          r.mean,
          r.mean === null ? null : classifyNdvi(r.mean),
          r.status,
          r.failure ?? null,
          r.cloud,
          JSON.stringify({ type: 'Feature', properties: { ndvi: r.mean }, geometry: null }),
        ],
      );
    }

    log('done.');
  });

  // eslint-disable-next-line no-console
  console.log('\nDemo accounts (development only):');
  console.log('  Farmer         ' + DEMO.farmer.phone + '  /  ' + DEMO.farmer.password);
  console.log('  Farm employee  ' + DEMO.employee.phone + '  /  ' + DEMO.employee.password + '   (linked to the farmer)');
  console.log('  Buyer          ' + DEMO.buyer.phone + '  /  ' + DEMO.buyer.password + '\n');

  await pool.end();
}

main().catch(async (err) => {
  // eslint-disable-next-line no-console
  console.error('[seed] failed:', err.message);
  try {
    await pool.end();
  } catch {
    /* ignore */
  }
  process.exit(1);
});
