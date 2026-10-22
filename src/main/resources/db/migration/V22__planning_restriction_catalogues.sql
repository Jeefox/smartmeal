-- Reference vocabulary only. No recipes or products are automatically declared safe.
INSERT INTO allergens(code,name) VALUES
    ('GLUTEN','Глютен'), ('MILK','Молоко'), ('EGGS','Яйца'), ('FISH','Рыба'),
    ('SOY','Соя'), ('PEANUTS','Арахис'), ('NUTS','Орехи'), ('SESAME','Кунжут')
ON CONFLICT(code) DO NOTHING;
INSERT INTO equipment(code,name) VALUES
    ('PAN','Сковорода'), ('POT','Кастрюля'), ('OVEN','Духовка'),
    ('MICROWAVE','Микроволновая печь'), ('BLENDER','Блендер')
ON CONFLICT(code) DO NOTHING;
