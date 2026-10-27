-- Reviewed legacy catalogue only: never infer appliance needs from a user's changed instructions.
-- IDs, names and instruction fingerprints must all match; existing links remain intact.
WITH reviewed(recipe_id,recipe_name,instruction_hash,equipment_code) AS (
VALUES
('95c026de-6777-473d-9f28-64e2358a2daa'::uuid,'Гречка с курицей и морковью','48f9dc9d2e4f0c6a6d703f319a2424c0','PAN'),
('95c026de-6777-473d-9f28-64e2358a2daa'::uuid,'Гречка с курицей и морковью','48f9dc9d2e4f0c6a6d703f319a2424c0','POT'),
('0be6b6c1-26fc-42d9-a99f-2daff709d2bd'::uuid,'Гречка с тушёными овощами','456331fa66d526e7de5af5595ea25b4b','PAN'),
('0be6b6c1-26fc-42d9-a99f-2daff709d2bd'::uuid,'Гречка с тушёными овощами','456331fa66d526e7de5af5595ea25b4b','POT'),
('67112f45-0e89-4448-bb5a-09fcdcd446b3'::uuid,'Картофель с творожным соусом','1c755169b9ba14da7e50920988a66b92','POT'),
('0f360707-1d42-42cd-b7bf-f56724ee8c56'::uuid,'Курица с брокколи','238d39e88e6b9b6abba22d4d0a375fa4','PAN'),
('b9a40afa-d3f0-4887-a5fe-d292fec2fc8b'::uuid,'Макароны с курицей и сыром','9b12748acc4767b55590066b1a854bd0','PAN'),
('b9a40afa-d3f0-4887-a5fe-d292fec2fc8b'::uuid,'Макароны с курицей и сыром','9b12748acc4767b55590066b1a854bd0','POT'),
('d7997bcd-a044-48aa-8490-31a689f2b5f5'::uuid,'Макароны с тунцом и помидорами','dfe89635a085dd86a3c725ea5dbeacb3','PAN'),
('d7997bcd-a044-48aa-8490-31a689f2b5f5'::uuid,'Макароны с тунцом и помидорами','dfe89635a085dd86a3c725ea5dbeacb3','POT'),
('7b8b0d81-7cd7-4f88-a3c7-06732d748dc3'::uuid,'Минтай с картофелем в духовке','ef950fe894597bc6fb91ee7e65c0da27','OVEN'),
('50356658-edb0-4bda-9e54-d277ab3c475b'::uuid,'Овощной суп с курицей','1d7a71451b08fc2ce7c044558401d933','POT'),
('2750a9ec-958d-45b5-8136-636fa0091bc2'::uuid,'Овсяная каша с бананом','2f18f44a78cdf910d5364817aab314e2','POT'),
('6bdda04b-2fd7-4a88-a156-9c72a044463d'::uuid,'Овсяная каша с яблоком и орехами','f203a2de28ed809b467fe566248b7a35','POT'),
('c1c47e20-3c5f-4407-ad40-d6272389de41'::uuid,'Омлет с брокколи','5f3f5ac60bfabe91af4048a5d9b9dac0','PAN'),
('c1c47e20-3c5f-4407-ad40-d6272389de41'::uuid,'Омлет с брокколи','5f3f5ac60bfabe91af4048a5d9b9dac0','POT'),
('004104f2-ebe9-4eb4-b93f-6e5e363783bf'::uuid,'Омлет с помидором и сыром','c0a9b0c9fca7e3591154adca650b2ea6','PAN'),
('f4f7006e-a7f9-434e-a8d2-fd6aed7ed32e'::uuid,'Рис с индейкой и брокколи','9bdade4e000e7ad142dcdd51d99eb863','PAN'),
('f4f7006e-a7f9-434e-a8d2-fd6aed7ed32e'::uuid,'Рис с индейкой и брокколи','9bdade4e000e7ad142dcdd51d99eb863','POT'),
('4535584e-53f7-46a1-8000-416eb3ea06bc'::uuid,'Рис с фасолью и овощами','e712efe3989b78339c45748899a4c9f9','PAN'),
('4535584e-53f7-46a1-8000-416eb3ea06bc'::uuid,'Рис с фасолью и овощами','e712efe3989b78339c45748899a4c9f9','POT'),
('318c003b-f1f2-4ab1-bab8-68e2ec33bf78'::uuid,'Салат с тунцом и яйцом','d57b7973150a12f15004490b1724b7e0','POT'),
('1eabb49c-184b-44f0-a890-e175b3c0f3e6'::uuid,'Чечевица с индейкой','e0dd1691a6092e409e56da6ede55cd59','PAN'),
('1eabb49c-184b-44f0-a890-e175b3c0f3e6'::uuid,'Чечевица с индейкой','e0dd1691a6092e409e56da6ede55cd59','POT'),
('bba38b69-0f99-4334-bfac-9260cdbc6a12'::uuid,'Чечевичный суп с овощами','5834a4c7b1fa0426cf2a01802f3abf83','POT')
)
INSERT INTO recipe_equipment(recipe_id,equipment_id)
SELECT r.id,e.id FROM reviewed v
JOIN recipes r ON r.id=v.recipe_id AND r.name=v.recipe_name AND md5(r.instructions)=v.instruction_hash
JOIN equipment e ON e.code=v.equipment_code
ON CONFLICT DO NOTHING;
