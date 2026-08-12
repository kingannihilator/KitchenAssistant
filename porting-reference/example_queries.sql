-- Example ingredient searches for recipe_database.sqlite

-- 1. Exact normalized ingredient:
SELECT r.recipe_id, r.title
FROM recipes r
JOIN recipe_ingredients ri ON ri.recipe_id=r.recipe_id
JOIN ingredients i ON i.ingredient_id=ri.ingredient_id
WHERE i.normalized_name='chicken'
ORDER BY r.title;

-- 2. Recipes containing ALL three ingredients:
SELECT r.recipe_id, r.title
FROM recipes r
JOIN recipe_ingredients ri ON ri.recipe_id=r.recipe_id
JOIN ingredients i ON i.ingredient_id=ri.ingredient_id
WHERE i.normalized_name IN ('chicken','potato','onion')
GROUP BY r.recipe_id
HAVING COUNT(DISTINCT i.normalized_name)=3
ORDER BY r.title;

-- 3. Recipes containing any of the requested ingredients, ranked by matches:
SELECT r.recipe_id, r.title, COUNT(DISTINCT i.normalized_name) AS matches
FROM recipes r
JOIN recipe_ingredients ri ON ri.recipe_id=r.recipe_id
JOIN ingredients i ON i.ingredient_id=ri.ingredient_id
WHERE i.normalized_name IN ('chicken','potato','onion','garlic')
GROUP BY r.recipe_id
ORDER BY matches DESC, r.title;

-- 4. Ingredient-name search using FTS5:
SELECT ingredient_id, name
FROM ingredient_fts
WHERE ingredient_fts MATCH 'chicken*';

-- 5. Full-text recipe search:
SELECT recipe_id, title
FROM recipe_fts
WHERE recipe_fts MATCH 'chicken AND garlic'
ORDER BY rank;
