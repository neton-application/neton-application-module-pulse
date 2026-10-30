-- pulse: 1.0.0-beta fresh-install schema. Not an upgrade path.
-- Apply through the migration runner; never edit after this baseline is released.
SET search_path = public;
--
--


SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: pulse_alert_records; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pulse_alert_records (
    id bigint NOT NULL,
    rule_id bigint NOT NULL,
    rule_name character varying(128) DEFAULT ''::character varying NOT NULL,
    type character varying(32) NOT NULL,
    project_id character varying(64),
    title character varying(255) DEFAULT ''::character varying NOT NULL,
    detail text DEFAULT ''::text NOT NULL,
    observed double precision DEFAULT 0 NOT NULL,
    threshold double precision DEFAULT 0 NOT NULL,
    delivery_status integer DEFAULT 0 NOT NULL,
    delivery_error character varying(512),
    fired_at bigint DEFAULT 0 NOT NULL,
    created_at bigint
);


--
-- Name: pulse_alert_records_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.pulse_alert_records_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: pulse_alert_records_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.pulse_alert_records_id_seq OWNED BY public.pulse_alert_records.id;


--
-- Name: pulse_alert_rules; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pulse_alert_rules (
    id bigint NOT NULL,
    name character varying(128) NOT NULL,
    type character varying(32) NOT NULL,
    project_id character varying(64),
    threshold double precision DEFAULT 0 NOT NULL,
    min_devices bigint DEFAULT 10 NOT NULL,
    cooldown_ms bigint DEFAULT 3600000 NOT NULL,
    webhook_url character varying(512),
    status integer DEFAULT 1 NOT NULL,
    last_fired_at bigint,
    created_at bigint,
    updated_at bigint
);


--
-- Name: pulse_alert_rules_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.pulse_alert_rules_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: pulse_alert_rules_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.pulse_alert_rules_id_seq OWNED BY public.pulse_alert_rules.id;


--
-- Name: pulse_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pulse_events (
    id bigint NOT NULL,
    event_id character varying(64) NOT NULL,
    session_id character varying(64) NOT NULL,
    ts bigint DEFAULT 0 NOT NULL,
    kind character varying(24) NOT NULL,
    name character varying(128) DEFAULT ''::character varying NOT NULL,
    source_module character varying(128),
    attributes text DEFAULT '{}'::text NOT NULL,
    created_at bigint,
    project_id character varying(64),
    device_id character varying(64),
    installation_id character varying(64),
    user_id character varying(64),
    app_version character varying(64),
    build_number bigint,
    platform character varying(16),
    package_name character varying(191),
    source_image_uuid character varying(36),
    sdk_version character varying(64)
);


--
-- Name: pulse_analytics_events; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.pulse_analytics_events AS
 SELECT id,
    event_id,
    session_id,
    ts,
    kind,
    name,
    source_module,
    attributes,
    created_at,
    project_id,
    device_id,
    installation_id,
    user_id,
    app_version,
    build_number,
    platform,
    package_name
   FROM public.pulse_events
  WHERE ((kind)::text = 'Analytics'::text);


--
-- Name: pulse_android_mappings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pulse_android_mappings (
    id bigint NOT NULL,
    app_key character varying(64) NOT NULL,
    build_number bigint NOT NULL,
    mapping text NOT NULL,
    created_at bigint
);


--
-- Name: pulse_android_mappings_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.pulse_android_mappings_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: pulse_android_mappings_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.pulse_android_mappings_id_seq OWNED BY public.pulse_android_mappings.id;


--
-- Name: pulse_apm_events; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.pulse_apm_events AS
 SELECT id,
    event_id,
    session_id,
    ts,
    kind,
    name,
    source_module,
    attributes,
    created_at,
    project_id,
    device_id,
    installation_id,
    user_id,
    app_version,
    build_number,
    platform,
    package_name
   FROM public.pulse_events
  WHERE ((kind)::text = ANY ((ARRAY['Crash'::character varying, 'Error'::character varying, 'Log'::character varying, 'Network'::character varying, 'Performance'::character varying, 'Breadcrumb'::character varying])::text[]));


--
-- Name: pulse_app_versions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pulse_app_versions (
    id bigint NOT NULL,
    app_id bigint NOT NULL,
    version_name character varying(64) NOT NULL,
    build_number bigint NOT NULL,
    release_notes text DEFAULT ''::text NOT NULL,
    download_url character varying(512),
    force_update boolean DEFAULT false NOT NULL,
    published integer DEFAULT 0 NOT NULL,
    published_at bigint,
    created_at bigint,
    updated_at bigint
);


--
-- Name: pulse_app_versions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.pulse_app_versions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: pulse_app_versions_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.pulse_app_versions_id_seq OWNED BY public.pulse_app_versions.id;


--
-- Name: pulse_apps; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pulse_apps (
    id bigint NOT NULL,
    name character varying(128) NOT NULL,
    platform character varying(16) NOT NULL,
    app_key character varying(64) NOT NULL,
    package_name character varying(191),
    remark character varying(255),
    status integer DEFAULT 1 NOT NULL,
    created_at bigint,
    updated_at bigint,
    package_policy character varying(16) DEFAULT 'strict'::character varying NOT NULL,
    allowed_packages text,
    collect_kinds character varying(255) DEFAULT ''::character varying NOT NULL,
    CONSTRAINT ck_pulse_apps_package_policy CHECK (((package_policy)::text = ANY ((ARRAY['strict'::character varying, 'allowlist'::character varying, 'any'::character varying])::text[])))
);


--
-- Name: pulse_apps_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.pulse_apps_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: pulse_apps_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.pulse_apps_id_seq OWNED BY public.pulse_apps.id;


--
-- Name: pulse_artifact_analyses; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pulse_artifact_analyses (
    id bigint NOT NULL,
    version_id bigint NOT NULL,
    file_name character varying(255) NOT NULL,
    file_size bigint NOT NULL,
    archive_sha256 character varying(64) NOT NULL,
    artifact_format character varying(32) NOT NULL,
    image_count integer DEFAULT 0 NOT NULL,
    swift_symbol_count integer DEFAULT 0 NOT NULL,
    cpp_symbol_count integer DEFAULT 0 NOT NULL,
    c_symbol_count integer DEFAULT 0 NOT NULL,
    imported_symbol_count integer DEFAULT 0 NOT NULL,
    signed_image_count integer DEFAULT 0 NOT NULL,
    created_at bigint NOT NULL
);


--
-- Name: pulse_artifact_analyses_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.pulse_artifact_analyses_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: pulse_artifact_analyses_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.pulse_artifact_analyses_id_seq OWNED BY public.pulse_artifact_analyses.id;


--
-- Name: pulse_artifact_debug_symbols; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pulse_artifact_debug_symbols (
    id bigint NOT NULL,
    analysis_id bigint NOT NULL,
    image_uuid character varying(64) NOT NULL,
    architecture character varying(32) NOT NULL,
    address bigint NOT NULL,
    symbol_name text NOT NULL,
    language character varying(16) NOT NULL
);


--
-- Name: pulse_artifact_debug_symbols_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.pulse_artifact_debug_symbols_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: pulse_artifact_debug_symbols_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.pulse_artifact_debug_symbols_id_seq OWNED BY public.pulse_artifact_debug_symbols.id;


--
-- Name: pulse_artifact_images; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pulse_artifact_images (
    id bigint NOT NULL,
    analysis_id bigint NOT NULL,
    relative_path text NOT NULL,
    architecture character varying(32) NOT NULL,
    image_uuid character varying(64),
    text_sha256 character varying(64) NOT NULL,
    text_size bigint NOT NULL,
    has_swift_metadata boolean DEFAULT false NOT NULL,
    swift_symbol_count integer DEFAULT 0 NOT NULL,
    cpp_symbol_count integer DEFAULT 0 NOT NULL,
    c_symbol_count integer DEFAULT 0 NOT NULL,
    imported_symbol_count integer DEFAULT 0 NOT NULL,
    symbols_truncated boolean DEFAULT false NOT NULL,
    swift_symbols text DEFAULT '[]'::text NOT NULL,
    cpp_symbols text DEFAULT '[]'::text NOT NULL,
    c_symbols text DEFAULT '[]'::text NOT NULL,
    imported_symbols text DEFAULT '[]'::text NOT NULL,
    linked_libraries text DEFAULT '[]'::text NOT NULL,
    signature_status character varying(32) NOT NULL,
    signing_certificates text DEFAULT '[]'::text NOT NULL,
    created_at bigint NOT NULL,
    debug_symbol_count integer DEFAULT 0 NOT NULL,
    debug_symbols_truncated boolean DEFAULT false NOT NULL,
    debug_symbol_sample text DEFAULT '[]'::text NOT NULL
);


--
-- Name: pulse_artifact_images_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.pulse_artifact_images_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: pulse_artifact_images_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.pulse_artifact_images_id_seq OWNED BY public.pulse_artifact_images.id;


--
-- Name: pulse_client_connections; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pulse_client_connections (
    id bigint NOT NULL,
    connection_id character varying(64) NOT NULL,
    project_id character varying(64) NOT NULL,
    device_id character varying(128) NOT NULL,
    installation_id character varying(128) NOT NULL,
    platform character varying(32) NOT NULL,
    device_type character varying(64) NOT NULL,
    package_name character varying(255),
    app_version character varying(64),
    build_number bigint DEFAULT 0 NOT NULL,
    sdk_version character varying(64) NOT NULL,
    capabilities text DEFAULT '[]'::text NOT NULL,
    connected_at bigint NOT NULL,
    last_heartbeat_at bigint NOT NULL,
    disconnected_at bigint,
    disconnect_reason character varying(128)
);


--
-- Name: pulse_client_connections_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.pulse_client_connections_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: pulse_client_connections_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.pulse_client_connections_id_seq OWNED BY public.pulse_client_connections.id;


--
-- Name: pulse_crash_issues; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pulse_crash_issues (
    id bigint NOT NULL,
    project_id character varying(64) DEFAULT ''::character varying NOT NULL,
    fingerprint character varying(64) NOT NULL,
    title character varying(191) DEFAULT ''::character varying NOT NULL,
    message character varying(512) DEFAULT ''::character varying NOT NULL,
    sample_frames text DEFAULT ''::text NOT NULL,
    sample_slide bigint DEFAULT 0 NOT NULL,
    app_key character varying(64),
    build_number bigint,
    first_seen_at bigint DEFAULT 0 NOT NULL,
    last_seen_at bigint DEFAULT 0 NOT NULL,
    event_count bigint DEFAULT 0 NOT NULL,
    device_count bigint DEFAULT 0 NOT NULL,
    status integer DEFAULT 0 NOT NULL,
    created_at bigint,
    updated_at bigint,
    kind character varying(16) DEFAULT 'Crash'::character varying NOT NULL,
    sample_stack text DEFAULT ''::text NOT NULL
);


--
-- Name: pulse_crash_issues_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.pulse_crash_issues_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: pulse_crash_issues_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.pulse_crash_issues_id_seq OWNED BY public.pulse_crash_issues.id;


--
-- Name: pulse_crash_sightings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pulse_crash_sightings (
    id bigint NOT NULL,
    issue_id bigint NOT NULL,
    device_id character varying(64) DEFAULT ''::character varying NOT NULL,
    build_number bigint,
    first_seen_at bigint DEFAULT 0 NOT NULL,
    last_seen_at bigint DEFAULT 0 NOT NULL
);


--
-- Name: pulse_crash_sightings_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.pulse_crash_sightings_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: pulse_crash_sightings_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.pulse_crash_sightings_id_seq OWNED BY public.pulse_crash_sightings.id;


--
-- Name: pulse_events_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.pulse_events_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: pulse_events_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.pulse_events_id_seq OWNED BY public.pulse_events.id;


--
-- Name: pulse_module_alerts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pulse_module_alerts (
    id bigint NOT NULL,
    project_id character varying(64) DEFAULT ''::character varying NOT NULL,
    module_name character varying(191) NOT NULL,
    rule_id bigint,
    severity character varying(16) DEFAULT 'info'::character varying NOT NULL,
    label character varying(128) DEFAULT ''::character varying NOT NULL,
    note character varying(512),
    first_seen_at bigint DEFAULT 0 NOT NULL,
    last_seen_at bigint DEFAULT 0 NOT NULL,
    occurrences bigint DEFAULT 0 NOT NULL,
    device_count bigint DEFAULT 0 NOT NULL,
    status integer DEFAULT 0 NOT NULL,
    created_at bigint,
    updated_at bigint,
    kind character varying(16) DEFAULT 'module'::character varying NOT NULL,
    api character varying(128),
    call_count bigint DEFAULT 0 NOT NULL,
    detail character varying(512)
);


--
-- Name: pulse_module_alerts_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.pulse_module_alerts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: pulse_module_alerts_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.pulse_module_alerts_id_seq OWNED BY public.pulse_module_alerts.id;


--
-- Name: pulse_module_rules; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pulse_module_rules (
    id bigint NOT NULL,
    pattern character varying(191) NOT NULL,
    label character varying(128) DEFAULT ''::character varying NOT NULL,
    severity character varying(16) DEFAULT 'info'::character varying NOT NULL,
    note character varying(512),
    app_id bigint,
    status integer DEFAULT 1 NOT NULL,
    created_at bigint,
    updated_at bigint
);


--
-- Name: pulse_module_rules_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.pulse_module_rules_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: pulse_module_rules_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.pulse_module_rules_id_seq OWNED BY public.pulse_module_rules.id;


--
-- Name: pulse_module_sightings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pulse_module_sightings (
    id bigint NOT NULL,
    alert_id bigint NOT NULL,
    device_id character varying(64) DEFAULT ''::character varying NOT NULL,
    app_version character varying(64),
    first_seen_at bigint DEFAULT 0 NOT NULL,
    last_seen_at bigint DEFAULT 0 NOT NULL
);


--
-- Name: pulse_module_sightings_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.pulse_module_sightings_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: pulse_module_sightings_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.pulse_module_sightings_id_seq OWNED BY public.pulse_module_sightings.id;


--
-- Name: pulse_security_events; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.pulse_security_events AS
 SELECT id,
    event_id,
    session_id,
    ts,
    kind,
    name,
    source_module,
    attributes,
    created_at,
    project_id,
    device_id,
    installation_id,
    user_id,
    app_version,
    build_number,
    platform,
    package_name
   FROM public.pulse_events
  WHERE ((kind)::text = 'Runtime'::text);


--
-- Name: pulse_symbols; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pulse_symbols (
    id bigint NOT NULL,
    app_key character varying(64) NOT NULL,
    build_number bigint NOT NULL,
    address bigint NOT NULL,
    symbol character varying(512) NOT NULL
);


--
-- Name: pulse_symbols_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.pulse_symbols_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: pulse_symbols_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.pulse_symbols_id_seq OWNED BY public.pulse_symbols.id;


--
-- Name: pulse_alert_records id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_alert_records ALTER COLUMN id SET DEFAULT nextval('public.pulse_alert_records_id_seq'::regclass);


--
-- Name: pulse_alert_rules id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_alert_rules ALTER COLUMN id SET DEFAULT nextval('public.pulse_alert_rules_id_seq'::regclass);


--
-- Name: pulse_android_mappings id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_android_mappings ALTER COLUMN id SET DEFAULT nextval('public.pulse_android_mappings_id_seq'::regclass);


--
-- Name: pulse_app_versions id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_app_versions ALTER COLUMN id SET DEFAULT nextval('public.pulse_app_versions_id_seq'::regclass);


--
-- Name: pulse_apps id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_apps ALTER COLUMN id SET DEFAULT nextval('public.pulse_apps_id_seq'::regclass);


--
-- Name: pulse_artifact_analyses id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_artifact_analyses ALTER COLUMN id SET DEFAULT nextval('public.pulse_artifact_analyses_id_seq'::regclass);


--
-- Name: pulse_artifact_debug_symbols id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_artifact_debug_symbols ALTER COLUMN id SET DEFAULT nextval('public.pulse_artifact_debug_symbols_id_seq'::regclass);


--
-- Name: pulse_artifact_images id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_artifact_images ALTER COLUMN id SET DEFAULT nextval('public.pulse_artifact_images_id_seq'::regclass);


--
-- Name: pulse_client_connections id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_client_connections ALTER COLUMN id SET DEFAULT nextval('public.pulse_client_connections_id_seq'::regclass);


--
-- Name: pulse_crash_issues id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_crash_issues ALTER COLUMN id SET DEFAULT nextval('public.pulse_crash_issues_id_seq'::regclass);


--
-- Name: pulse_crash_sightings id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_crash_sightings ALTER COLUMN id SET DEFAULT nextval('public.pulse_crash_sightings_id_seq'::regclass);


--
-- Name: pulse_events id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_events ALTER COLUMN id SET DEFAULT nextval('public.pulse_events_id_seq'::regclass);


--
-- Name: pulse_module_alerts id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_module_alerts ALTER COLUMN id SET DEFAULT nextval('public.pulse_module_alerts_id_seq'::regclass);


--
-- Name: pulse_module_rules id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_module_rules ALTER COLUMN id SET DEFAULT nextval('public.pulse_module_rules_id_seq'::regclass);


--
-- Name: pulse_module_sightings id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_module_sightings ALTER COLUMN id SET DEFAULT nextval('public.pulse_module_sightings_id_seq'::regclass);


--
-- Name: pulse_symbols id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_symbols ALTER COLUMN id SET DEFAULT nextval('public.pulse_symbols_id_seq'::regclass);


--
-- Name: pulse_alert_records pulse_alert_records_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_alert_records
    ADD CONSTRAINT pulse_alert_records_pkey PRIMARY KEY (id);


--
-- Name: pulse_alert_rules pulse_alert_rules_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_alert_rules
    ADD CONSTRAINT pulse_alert_rules_pkey PRIMARY KEY (id);


--
-- Name: pulse_android_mappings pulse_android_mappings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_android_mappings
    ADD CONSTRAINT pulse_android_mappings_pkey PRIMARY KEY (id);


--
-- Name: pulse_app_versions pulse_app_versions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_app_versions
    ADD CONSTRAINT pulse_app_versions_pkey PRIMARY KEY (id);


--
-- Name: pulse_apps pulse_apps_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_apps
    ADD CONSTRAINT pulse_apps_pkey PRIMARY KEY (id);


--
-- Name: pulse_artifact_analyses pulse_artifact_analyses_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_artifact_analyses
    ADD CONSTRAINT pulse_artifact_analyses_pkey PRIMARY KEY (id);


--
-- Name: pulse_artifact_debug_symbols pulse_artifact_debug_symbols_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_artifact_debug_symbols
    ADD CONSTRAINT pulse_artifact_debug_symbols_pkey PRIMARY KEY (id);


--
-- Name: pulse_artifact_images pulse_artifact_images_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_artifact_images
    ADD CONSTRAINT pulse_artifact_images_pkey PRIMARY KEY (id);


--
-- Name: pulse_client_connections pulse_client_connections_connection_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_client_connections
    ADD CONSTRAINT pulse_client_connections_connection_id_key UNIQUE (connection_id);


--
-- Name: pulse_client_connections pulse_client_connections_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_client_connections
    ADD CONSTRAINT pulse_client_connections_pkey PRIMARY KEY (id);


--
-- Name: pulse_crash_issues pulse_crash_issues_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_crash_issues
    ADD CONSTRAINT pulse_crash_issues_pkey PRIMARY KEY (id);


--
-- Name: pulse_crash_sightings pulse_crash_sightings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_crash_sightings
    ADD CONSTRAINT pulse_crash_sightings_pkey PRIMARY KEY (id);


--
-- Name: pulse_events pulse_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_events
    ADD CONSTRAINT pulse_events_pkey PRIMARY KEY (id);


--
-- Name: pulse_module_alerts pulse_module_alerts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_module_alerts
    ADD CONSTRAINT pulse_module_alerts_pkey PRIMARY KEY (id);


--
-- Name: pulse_module_rules pulse_module_rules_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_module_rules
    ADD CONSTRAINT pulse_module_rules_pkey PRIMARY KEY (id);


--
-- Name: pulse_module_sightings pulse_module_sightings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_module_sightings
    ADD CONSTRAINT pulse_module_sightings_pkey PRIMARY KEY (id);


--
-- Name: pulse_symbols pulse_symbols_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_symbols
    ADD CONSTRAINT pulse_symbols_pkey PRIMARY KEY (id);


--
-- Name: idx_pulse_alert_records_rule; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pulse_alert_records_rule ON public.pulse_alert_records USING btree (rule_id, fired_at DESC);


--
-- Name: idx_pulse_alert_records_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pulse_alert_records_time ON public.pulse_alert_records USING btree (fired_at DESC);


--
-- Name: idx_pulse_alert_rules_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pulse_alert_rules_active ON public.pulse_alert_rules USING btree (status, type);


--
-- Name: idx_pulse_app_versions_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pulse_app_versions_lookup ON public.pulse_app_versions USING btree (app_id, published, build_number);


--
-- Name: idx_pulse_apps_package; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pulse_apps_package ON public.pulse_apps USING btree (package_name);


--
-- Name: idx_pulse_artifact_debug_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pulse_artifact_debug_lookup ON public.pulse_artifact_debug_symbols USING btree (analysis_id, image_uuid, address);


--
-- Name: idx_pulse_artifact_images_analysis; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pulse_artifact_images_analysis ON public.pulse_artifact_images USING btree (analysis_id);


--
-- Name: idx_pulse_artifact_images_uuid; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pulse_artifact_images_uuid ON public.pulse_artifact_images USING btree (image_uuid);


--
-- Name: idx_pulse_connections_app_online; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pulse_connections_app_online ON public.pulse_client_connections USING btree (project_id, disconnected_at, last_heartbeat_at DESC);


--
-- Name: idx_pulse_connections_sdk; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pulse_connections_sdk ON public.pulse_client_connections USING btree (project_id, sdk_version, connected_at DESC);


--
-- Name: idx_pulse_crash_issues_kind; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pulse_crash_issues_kind ON public.pulse_crash_issues USING btree (project_id, kind, last_seen_at);


--
-- Name: idx_pulse_crash_issues_rank; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pulse_crash_issues_rank ON public.pulse_crash_issues USING btree (status, device_count, last_seen_at);


--
-- Name: idx_pulse_crash_sightings_build; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pulse_crash_sightings_build ON public.pulse_crash_sightings USING btree (build_number, issue_id);


--
-- Name: idx_pulse_events_build; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pulse_events_build ON public.pulse_events USING btree (project_id, build_number, ts);


--
-- Name: idx_pulse_events_device; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pulse_events_device ON public.pulse_events USING btree (device_id, ts);


--
-- Name: idx_pulse_events_kind; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pulse_events_kind ON public.pulse_events USING btree (kind, ts);


--
-- Name: idx_pulse_events_project_name_ts; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pulse_events_project_name_ts ON public.pulse_events USING btree (project_id, name, ts);


--
-- Name: idx_pulse_events_project_ts; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pulse_events_project_ts ON public.pulse_events USING btree (project_id, ts);


--
-- Name: idx_pulse_events_runtime_package; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pulse_events_runtime_package ON public.pulse_events USING btree (project_id, platform, package_name, ts);


--
-- Name: idx_pulse_events_sdk_version; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pulse_events_sdk_version ON public.pulse_events USING btree (project_id, sdk_version, ts);


--
-- Name: idx_pulse_events_session; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pulse_events_session ON public.pulse_events USING btree (session_id);


--
-- Name: idx_pulse_events_source_image; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pulse_events_source_image ON public.pulse_events USING btree (project_id, source_module, source_image_uuid, ts);


--
-- Name: idx_pulse_events_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pulse_events_user ON public.pulse_events USING btree (user_id, ts);


--
-- Name: idx_pulse_module_alerts_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pulse_module_alerts_status ON public.pulse_module_alerts USING btree (status, severity, last_seen_at);


--
-- Name: idx_pulse_module_rules_app; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pulse_module_rules_app ON public.pulse_module_rules USING btree (app_id, status);


--
-- Name: idx_pulse_symbols_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pulse_symbols_lookup ON public.pulse_symbols USING btree (app_key, build_number, address);


--
-- Name: uk_pulse_android_mappings; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_pulse_android_mappings ON public.pulse_android_mappings USING btree (app_key, build_number);


--
-- Name: uk_pulse_app_versions_build; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_pulse_app_versions_build ON public.pulse_app_versions USING btree (app_id, build_number);


--
-- Name: uk_pulse_apps_app_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_pulse_apps_app_key ON public.pulse_apps USING btree (app_key);


--
-- Name: uk_pulse_artifact_analysis_version; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_pulse_artifact_analysis_version ON public.pulse_artifact_analyses USING btree (version_id);


--
-- Name: uk_pulse_crash_issues; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_pulse_crash_issues ON public.pulse_crash_issues USING btree (project_id, fingerprint);


--
-- Name: uk_pulse_crash_sightings; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_pulse_crash_sightings ON public.pulse_crash_sightings USING btree (issue_id, device_id, build_number);


--
-- Name: uk_pulse_events_eventid; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_pulse_events_eventid ON public.pulse_events USING btree (event_id);


--
-- Name: uk_pulse_module_alerts; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_pulse_module_alerts ON public.pulse_module_alerts USING btree (project_id, kind, module_name, COALESCE(api, ''::character varying), COALESCE(detail, ''::character varying));


--
-- Name: uk_pulse_module_sightings; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_pulse_module_sightings ON public.pulse_module_sightings USING btree (alert_id, device_id);


--
-- Name: pulse_artifact_analyses pulse_artifact_analyses_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_artifact_analyses
    ADD CONSTRAINT pulse_artifact_analyses_version_id_fkey FOREIGN KEY (version_id) REFERENCES public.pulse_app_versions(id) ON DELETE CASCADE;


--
-- Name: pulse_artifact_debug_symbols pulse_artifact_debug_symbols_analysis_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_artifact_debug_symbols
    ADD CONSTRAINT pulse_artifact_debug_symbols_analysis_id_fkey FOREIGN KEY (analysis_id) REFERENCES public.pulse_artifact_analyses(id) ON DELETE CASCADE;


--
-- Name: pulse_artifact_images pulse_artifact_images_analysis_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pulse_artifact_images
    ADD CONSTRAINT pulse_artifact_images_analysis_id_fkey FOREIGN KEY (analysis_id) REFERENCES public.pulse_artifact_analyses(id) ON DELETE CASCADE;


--
--


SET search_path = public;
