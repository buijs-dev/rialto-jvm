-- Informative PostgreSQL storage input for the neutral Shipment example.
-- Not a generated migration; not executed by the specification checker.
CREATE TABLE warehouse (
    storage_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL
);
CREATE TABLE customs_declaration (id UUID PRIMARY KEY, contents TEXT NOT NULL);
CREATE TABLE handling_unit (
    id UUID PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN ('pallet', 'container')),
    capacity_kg INTEGER,
    seal TEXT,
    CHECK ((kind = 'pallet' AND capacity_kg >= 1 AND seal IS NULL)
        OR (kind = 'container' AND seal IS NOT NULL AND capacity_kg IS NULL))
);
CREATE TABLE shipment (
    storage_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    version INTEGER NOT NULL CHECK (version >= 1),
    delivery_note VARCHAR(280),
    expected_delivery DATE,
    destination_street VARCHAR(200) NOT NULL,
    destination_city VARCHAR(120) NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('draft', 'approved', 'rejected')),
    warehouse_id BIGINT REFERENCES warehouse(storage_id),
    priority TEXT NOT NULL DEFAULT 'standard',
    recipient JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    approved_by TEXT,
    approved_at TIMESTAMPTZ,
    rejection_reason VARCHAR(280),
    label TEXT NOT NULL,
    service_codes TEXT[] NOT NULL,
    tenant_id TEXT NOT NULL,
    handling_unit_id UUID REFERENCES handling_unit(id),
    customs_declaration_id UUID REFERENCES customs_declaration(id) ON DELETE RESTRICT
);
CREATE TABLE shipment_line (
    public_id UUID PRIMARY KEY,
    shipment_id BIGINT NOT NULL REFERENCES shipment(storage_id),
    quantity INTEGER NOT NULL CHECK (quantity >= 1),
    position INTEGER NOT NULL
);
-- Ownership is a metadata / application policy, not inferred from these FKs.
-- Aggregate operations update Shipment.version whenever its response representation changes.

CREATE TABLE tag (id UUID PRIMARY KEY, name TEXT NOT NULL);











CREATE TABLE shipment_tag (
    shipment_id BIGINT NOT NULL REFERENCES shipment(storage_id) ON DELETE CASCADE,
    tag_id UUID NOT NULL REFERENCES tag(id),
    PRIMARY KEY (shipment_id, tag_id)
);
CREATE TABLE tracking_scan (
    id UUID PRIMARY KEY,
    shipment_id BIGINT NOT NULL REFERENCES shipment(storage_id),
    scanned_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE shipment_package (
    id UUID PRIMARY KEY,
    shipment_id BIGINT NOT NULL REFERENCES shipment(storage_id) ON DELETE CASCADE,
    weight_kg DECIMAL(8,3) NOT NULL CHECK (weight_kg > 0),
    position INTEGER NOT NULL
);
CREATE TABLE carrier (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL UNIQUE,
    contact_email TEXT,
    deleted_at TIMESTAMPTZ,
    deleted_by TEXT
);
CREATE TABLE carrier_rate (
    carrier_id UUID NOT NULL REFERENCES carrier(id),
    zone TEXT NOT NULL,
    amount DECIMAL(10,2) NOT NULL CHECK (amount >= 0),
    PRIMARY KEY (carrier_id, zone)
);
-- The content is in a blob store; the row keeps its key and metadata (persistence P7).
CREATE TABLE delivery_proof (
    shipment_id UUID PRIMARY KEY,
    file_key VARCHAR(200) NOT NULL,
    file_media_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    file_sha256 CHAR(64) NOT NULL,
    tenant_id TEXT NOT NULL
);
-- A user's saved shipment list filters; tenant_id and owner_id are scope columns (persistence P6.11).
CREATE TABLE saved_search (
    id UUID PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    filter VARCHAR(1000) NOT NULL,
    tenant_id TEXT NOT NULL,
    owner_id TEXT NOT NULL
);
