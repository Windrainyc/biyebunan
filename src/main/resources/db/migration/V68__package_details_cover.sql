ALTER TABLE service_package ADD COLUMN details text, ADD COLUMN cover_asset_id uuid REFERENCES asset(id);
