import { Router } from 'express';

import authRoutes from './auth/auth.routes.js';
import usersRoutes from './users/users.routes.js';
import accountRoutes from './account/account.routes.js';
import farmsRoutes from './farms/farms.routes.js';
import farmRecordsRoutes from './farm/records.routes.js';
import financeRoutes from './finance/finance.routes.js';
import satelliteRoutes from './satellite/satellite.routes.js';
import uploadsRoutes from './uploads/uploads.routes.js';
import marketplaceRoutes from './marketplace/marketplace.routes.js';
import ordersRoutes from './orders/orders.routes.js';
import notificationsRoutes from './notifications/notifications.routes.js';
import syncRoutes from './sync/sync.routes.js';
import publicRoutes from './public/public.routes.js';
import appRoutes from './app/app.routes.js';

const api = Router();

api.use('/auth', authRoutes);
api.use('/users', usersRoutes);
api.use('/', accountRoutes); // /users/me/deletion-request, /public is separate
api.use('/farms', farmsRoutes);
api.use('/farm', farmRecordsRoutes);
api.use('/finance', financeRoutes);
api.use('/satellite', satelliteRoutes);
api.use('/uploads', uploadsRoutes);
api.use('/marketplace', marketplaceRoutes);
api.use('/orders', ordersRoutes);
api.use('/notifications', notificationsRoutes);
api.use('/sync', syncRoutes);
api.use('/public', publicRoutes);
// Public on purpose: a device must be able to check for an update before login,
// and must keep working when this check is unavailable.
api.use('/app', appRoutes);

// Convenience: /api/v1/version
api.get('/version', (_req, res) => {
  res.json({ version: '1.0.0', apiVersion: 'v1' });
});

export default api;
