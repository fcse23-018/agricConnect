-- AgriConnect — create the Supabase Storage bucket image uploads need.
--
-- WHY THIS EXISTS
-- Every image (avatar, produce listing, plot photo) is stored in Supabase Storage;
-- the database keeps only the origin-relative path `/uploads/<uuid>.<ext>`. The API
-- uploads nothing to local disk in production because Render's free filesystem is
-- ephemeral — a file written to disk is destroyed on every redeploy and every
-- spindown, so an avatar would silently vanish.
--
-- When the bucket does not exist, Supabase answers every upload with
--   {"statusCode":"404","error":"Bucket not found","code":"NoSuchBucket"} (HTTP 400)
-- `POST /api/v1/uploads` then returns a generic 500 and the client falls back to its
-- image placeholder — which is what "uploaded photos render blank" actually is.
--
-- The API also tries to create the bucket itself on first upload
-- (`ensureBucket()` in backend/src/services/uploads.js), but that call only works if
-- SUPABASE_SERVICE_ROLE_KEY is the true service_role secret. Running this once in the
-- Supabase SQL editor makes the bucket exist regardless, and turns a silent failure
-- into one less thing to diagnose.
--
-- HOW TO RUN
--   Supabase Dashboard -> your project -> SQL Editor -> New query -> paste -> Run.
--   Run it against the same project that SUPABASE_URL points at.
--
-- The values below mirror backend/src/config/index.js and render.yaml. If you change
-- SUPABASE_STORAGE_BUCKET or MAX_UPLOAD_BYTES there, change them here too.
--   bucket          SUPABASE_STORAGE_BUCKET   = 'agriconnect-uploads'
--   public          /uploads/<name> 302-redirects to /storage/v1/object/public/...
--   file_size_limit MAX_UPLOAD_BYTES          = 2097152  (2 MB — the config default)
--   mime types      IMAGE_MIME_EXT keys in backend/src/services/uploads.js

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'agriconnect-uploads',
  'agriconnect-uploads',
  true,
  2097152,
  array['image/jpeg', 'image/png', 'image/webp']
)
on conflict (id) do update
  set public             = excluded.public,
      file_size_limit    = excluded.file_size_limit,
      allowed_mime_types = excluded.allowed_mime_types;

-- ACCESS CONTROL — why there is no RLS policy here
-- `public = true` is what lets a phone fetch an image by URL with no session.
-- Supabase serves public buckets through /storage/v1/object/public/... without
-- consulting row-level security, so no policy is needed for reads and none is added.
-- Writes and deletes happen with the service-role key, which bypasses RLS and is
-- held only by the server — the Android client never carries a privileged credential.
--
-- If images ever need to be private, set public = false and have the API hand out
-- short-lived signed URLs instead of redirecting to a public URL.
