import { z } from 'zod';

const MAX_QUANTITY = 999_999_999_999;

/**
 * Body for POST /orders. `quantity` must be > 0 (the DB CHECK is `quantity > 0`);
 * the actual available-stock comparison happens transactionally in the service.
 */
export const createOrderSchema = z
  .object({
    listingId: z.string().uuid(),
    quantity: z.coerce.number().positive().max(MAX_QUANTITY),
    note: z.string().trim().max(2000).nullish(),
  })
  .strict();

export const idParamSchema = z.object({
  id: z.string().uuid({ message: 'A valid order identifier is required.' }),
});

export default { createOrderSchema, idParamSchema };
