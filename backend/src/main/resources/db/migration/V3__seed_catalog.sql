-- =============================================================================
-- PayPilot AI — catalog & offer seed (Phase 2)
--
-- Deterministic demo dataset. Natural keys (slug/sku/code) are used instead of
-- explicit ids because identity columns forbid manual inserts.
-- =============================================================================

INSERT INTO product_categories (name, slug) VALUES
    ('Footwear',    'footwear'),
    ('Laptops',     'laptops'),
    ('Audio',       'audio'),
    ('Smartphones', 'smartphones'),
    ('Wearables',   'wearables'),
    ('Fitness',     'fitness');

-- ------------------------------- Footwear ------------------------------------
INSERT INTO products (category_id, sku, brand, title, description, price_paise, rating, attributes) VALUES
((SELECT id FROM product_categories WHERE slug = 'footwear'), 'SHOE-NK-DOWN12',  'Nike',    'Nike Downshifter 12 Road Running Shoes',  'Lightweight cushioning for everyday runs.',            369900, 4.3, '{"color":"Black/Iron Grey","size":"UK 9","use_case":"daily running","material":"Mesh"}'),
((SELECT id FROM product_categories WHERE slug = 'footwear'), 'SHOE-NK-REV7',    'Nike',    'Nike Revolution 7 Road Running Shoes',    'Soft foam midsole, breathable knit upper.',            319900, 4.2, '{"color":"Midnight Navy","size":"UK 9","use_case":"daily running","material":"Knit"}'),
((SELECT id FROM product_categories WHERE slug = 'footwear'), 'SHOE-AD-GALAXY6', 'Adidas',  'Adidas Galaxy 6 Road Running Shoes',      'Cloudfoam comfort for daily miles.',                   299900, 4.1, '{"color":"Core Black","size":"UK 9","use_case":"daily running","material":"Textile"}'),
((SELECT id FROM product_categories WHERE slug = 'footwear'), 'SHOE-AD-FALCN5',  'Adidas',  'Adidas Runfalcon 5 Trail-Ready Runner',   'Grippy outsole, snug fit for beginners.',              354900, 4.0, '{"color":"Lucid Blue","size":"UK 9","use_case":"daily running","material":"Mesh"}'),
((SELECT id FROM product_categories WHERE slug = 'footwear'), 'SHOE-PM-SOFTRIDE','Puma',    'Puma Softride Enzo Evo Running Shoes',    'Plush Softride foam for long walks and jogs.',         449900, 4.2, '{"color":"Grey/Violet","size":"UK 9","use_case":"daily running","material":"Mesh"}'),
((SELECT id FROM product_categories WHERE slug = 'footwear'), 'SHOE-AS-GELC8',   'ASICS',   'ASICS Gel-Contend 8 Running Shoes',       'Rearfoot GEL cushioning, durable stance.',             479900, 4.4, '{"color":"Sheet Rock","size":"UK 9","use_case":"daily running","material":"Synthetic"}'),
((SELECT id FROM product_categories WHERE slug = 'footwear'), 'SHOE-NK-PEG41',   'Nike',    'Nike Air Zoom Pegasus 41 Racing Shoes',   'Race-day responsiveness with Zoom Air units.',         899900, 4.6, '{"color":"Total Orange","size":"UK 9","use_case":"racing","material":"Engineered Mesh"}');

-- ------------------------------- Laptops -------------------------------------
INSERT INTO products (category_id, sku, brand, title, description, price_paise, rating, attributes) VALUES
((SELECT id FROM product_categories WHERE slug = 'laptops'), 'LAP-LN-SLIM3',   'Lenovo', 'Lenovo IdeaPad Slim 3 14" Laptop',   'Everyday productivity on a light chassis.',   4499000, 4.2, '{"processor":"Intel Core i5-12450H","ram":"16GB","storage":"512GB SSD","display":"14\" WUXGA"}'),
((SELECT id FROM product_categories WHERE slug = 'laptops'), 'LAP-AC-ALITE',   'Acer',   'Acer Aspire Lite 15" Laptop',        'Big screen value for students.',              4799000, 4.0, '{"processor":"Intel Core i5-1235U","ram":"16GB","storage":"512GB SSD","display":"15.6\" FHD"}'),
((SELECT id FROM product_categories WHERE slug = 'laptops'), 'LAP-AS-VIVO14',  'ASUS',   'ASUS Vivobook 14 Thin & Light',      'Fast i5-H performance, military-grade build.',5299000, 4.3, '{"processor":"Intel Core i5-12500H","ram":"16GB","storage":"512GB SSD","display":"14\" FHD"}'),
((SELECT id FROM product_categories WHERE slug = 'laptops'), 'LAP-HP-PAV14',   'HP',     'HP Pavilion 14 Ultra Slim',          'Premium finish with 1TB fast storage.',       5899000, 4.3, '{"processor":"Intel Core i5-1335U","ram":"16GB","storage":"1TB SSD","display":"14\" WUXGA"}'),
((SELECT id FROM product_categories WHERE slug = 'laptops'), 'LAP-DL-INSP15',  'Dell',   'Dell Inspiron 15 Everyday Laptop',   'Roomy 15-inch workhorse with spill-safe kb.', 6599000, 4.1, '{"processor":"Intel Core i7-1355U","ram":"16GB","storage":"512GB SSD","display":"15.6\" FHD"}'),
((SELECT id FROM product_categories WHERE slug = 'laptops'), 'LAP-AP-MBA-M1',  'Apple',  'MacBook Air M1 13"',                 'Silent fanless design, all-day battery.',     7999000, 4.7, '{"processor":"Apple M1","ram":"8GB","storage":"256GB SSD","display":"13.3\" Retina"}');

-- -------------------------------- Audio --------------------------------------
INSERT INTO products (category_id, sku, brand, title, description, price_paise, rating, attributes) VALUES
((SELECT id FROM product_categories WHERE slug = 'audio'), 'AUD-BT-RKZ450',  'boAt',       'boAt Rockerz 450 Bluetooth Headphones', '45h playback, padded earcups.',          129900, 4.1, '{"type":"on-ear","battery_hours":45,"anc":false}'),
((SELECT id FROM product_categories WHERE slug = 'audio'), 'AUD-NB-VS104M',  'Noise',      'Noise Buds VS104 Max TWS Earbuds',      'Quad-mic ENC, 100h total playtime.',     149900, 4.0, '{"type":"tws","battery_hours":100,"anc":false}'),
((SELECT id FROM product_categories WHERE slug = 'audio'), 'AUD-JB-TUNE510', 'JBL',        'JBL Tune 510BT Wireless On-Ear',        'Pure Bass sound, 57h battery.',          399900, 4.3, '{"type":"on-ear","battery_hours":57,"anc":false}'),
((SELECT id FROM product_categories WHERE slug = 'audio'), 'AUD-SN-CH520',   'Sony',       'Sony WH-CH520 Wireless Headphones',     'Lightweight, multipoint connection.',    599900, 4.4, '{"type":"on-ear","battery_hours":50,"anc":false}'),
((SELECT id FROM product_categories WHERE slug = 'audio'), 'AUD-SE-HD450SE', 'Sennheiser', 'Sennheiser HD 450SE Over-Ear ANC',      'Active noise cancellation, aptX.',       799900, 4.5, '{"type":"over-ear","battery_hours":60,"anc":true}'),
((SELECT id FROM product_categories WHERE slug = 'audio'), 'AUD-AP-APODS2',  'Apple',      'Apple AirPods (2nd Generation)',        'Seamless Apple ecosystem pairing.',     1990000, 4.5, '{"type":"tws","battery_hours":24,"anc":false}');

-- ----------------------------- Smartphones -----------------------------------
INSERT INTO products (category_id, sku, brand, title, description, price_paise, rating, attributes) VALUES
((SELECT id FROM product_categories WHERE slug = 'smartphones'), 'PHN-XM-NOTE13',  'Xiaomi',  'Redmi Note 13 5G',           '108MP camera, 120Hz AMOLED.',        1599900, 4.3, '{"display":"6.67\" AMOLED 120Hz","ram":"8GB","storage":"128GB","battery_mah":5000}'),
((SELECT id FROM product_categories WHERE slug = 'smartphones'), 'PHN-MT-G84',     'Motorola','Moto G84 5G',                'pOLED display, near-stock Android.', 1699900, 4.2, '{"display":"6.55\" pOLED","ram":"12GB","storage":"256GB","battery_mah":5000}'),
((SELECT id FROM product_categories WHERE slug = 'smartphones'), 'PHN-SM-M35',     'Samsung', 'Samsung Galaxy M35 5G',      'Exynos 1380, 4 OS upgrades.',        1899900, 4.3, '{"display":"6.6\" sAMOLED","ram":"8GB","storage":"128GB","battery_mah":6000}'),
((SELECT id FROM product_categories WHERE slug = 'smartphones'), 'PHN-NT-PH2A',    'Nothing', 'Nothing Phone (2a) 5G',      'Glyph interface, clean UI.',         2399900, 4.4, '{"display":"6.7\" AMOLED","ram":"8GB","storage":"128GB","battery_mah":5000}'),
((SELECT id FROM product_categories WHERE slug = 'smartphones'), 'PHN-AP-IP13',    'Apple',   'iPhone 13',                  'A15 Bionic, dual 12MP system.',      5299900, 4.7, '{"display":"6.1\" Super Retina XDR","ram":"4GB","storage":"128GB","battery_mah":3240}');

-- ------------------------------ Wearables ------------------------------------
INSERT INTO products (category_id, sku, brand, title, description, price_paise, rating, attributes) VALUES
((SELECT id FROM product_categories WHERE slug = 'wearables'), 'WRB-BT-WAVENEO2','boAt',    'boAt Wave Neo 2 Smartwatch',     '1.69" display, 700+ watch faces.', 179900, 4.0, '{"display":"1.69\" TFT","battery_days":7,"water_resistance":"IP68"}'),
((SELECT id FROM product_categories WHERE slug = 'wearables'), 'WRB-NS-CFP5',    'Noise',   'Noise ColorFit Pro 5 Smartwatch','BT calling, 1.85" AMOLED.',        349900, 4.2, '{"display":"1.85\" AMOLED","battery_days":7,"water_resistance":"IP68"}'),
((SELECT id FROM product_categories WHERE slug = 'wearables'), 'WRB-SM-FIT3',    'Samsung', 'Samsung Galaxy Fit3 Band',       'Fall detection, 13-day battery.',   399900, 4.3, '{"display":"1.6\" AMOLED","battery_days":13,"water_resistance":"5ATM"}'),
((SELECT id FROM product_categories WHERE slug = 'wearables'), 'WRB-FB-INSPIRE3','Fitbit',  'Fitbit Inspire 3 Health Tracker','Stress management, SpO2.',          899900, 4.4, '{"display":"OLED","battery_days":10,"water_resistance":"5ATM"}'),
((SELECT id FROM product_categories WHERE slug = 'wearables'), 'WRB-AP-WSE2',    'Apple',   'Apple Watch SE (2nd Gen)',       'Crash detection, family setup.',   2490000, 4.6, '{"display":"Retina LTPO","battery_days":1.5,"water_resistance":"5ATM"}');

-- ------------------------------- Fitness -------------------------------------
INSERT INTO products (category_id, sku, brand, title, description, price_paise, rating, attributes) VALUES
((SELECT id FROM product_categories WHERE slug = 'fitness'), 'FIT-AB-YOGAMAT', 'AmazonBasics','AmazonBasics Yoga Mat 6mm', 'Extra thickness, anti-skid.',        99900, 4.3, '{"material":"PVC","length_cm":173,"thickness_mm":6}'),
((SELECT id FROM product_categories WHERE slug = 'fitness'), 'FIT-BF-ROPE',    'Boldfit',     'Boldfit Speed Skipping Rope','Adjustable steel-wire rope.',       29900, 4.2, '{"material":"Steel/PVC","length_cm":300,"bearing":"ball"}'),
((SELECT id FROM product_categories WHERE slug = 'fitness'), 'FIT-KR-DBL10',   'Kore',        'Kore PVC Dumbbells 10kg Set','Anti-roll design, 2x5kg.',         249900, 4.4, '{"material":"PVC","weight_kg":10,"pieces":2}');

-- ------------------------------ Inventory ------------------------------------
-- Deterministic stock levels: premium items scarcer than commodity ones.
INSERT INTO inventory (product_id, available)
SELECT p.id,
       CASE WHEN p.price_paise >= 3000000 THEN 35
            WHEN p.price_paise >= 1000000 THEN 60
            ELSE 120 END
FROM products p;

-- -------------------------------- Offers -------------------------------------
INSERT INTO offers (code, type, discount_value, max_discount_paise, min_cart_paise, valid_from, valid_to, usage_limit_per_user, active) VALUES
('WELCOME10',  'PERCENTAGE', 1000,  50000, 100000, now() - interval '30 days', now() + interval '180 days', 1, TRUE),
('FLAT500',    'FLAT',       50000,  NULL,   400000, now() - interval '30 days', now() + interval '180 days', 1, TRUE),
('FOOTWEAR15', 'PERCENTAGE', 1500,  75000,  200000, now() - interval '30 days', now() + interval '180 days', 2, TRUE),
('TECH5',      'PERCENTAGE', 500,   200000, 3000000,now() - interval '30 days', now() + interval '180 days', 1, TRUE),
('MEGA20',     'PERCENTAGE', 2000,  150000, 500000, now() - interval '30 days', now() + interval '180 days', 1, TRUE);
