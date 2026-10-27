import copy
import json
import unittest
from pathlib import Path
from import_recipe_catalogue import validate_profile, validate_equipment

class CatalogueProfilesTest(unittest.TestCase):
    def setUp(self):
        self.recipes = json.loads((Path(__file__).resolve().parents[1] / "data/recipes/expanded-home-recipes.json").read_text())
        self.profile = self.recipes[0]["portionProfile"]

    def test_every_new_recipe_has_a_valid_profile_and_unique_key_and_name(self):
        self.assertEqual(16, len(self.recipes))
        self.assertEqual(16, len({r["key"] for r in self.recipes}))
        self.assertEqual(16, len({r["name"] for r in self.recipes}))
        for r in self.recipes:
            validate_profile(r["portionProfile"])
            self.assertGreaterEqual(len(r["steps"]), 4)
            self.assertGreater(len(r["ingredients"]), 0)
            self.assertEqual(len(r["ingredients"]), len({i["productName"] for i in r["ingredients"]}))
            if not r["original"]:
                self.assertTrue(r["sourceUrl"].startswith("https://"))
                self.assertTrue(r["adaptation"])

    def test_invalid_yields_and_ranges_fail_before_database_access(self):
        for change in [{"cookedYieldG": 0}, {"cookedYieldG": "NaN"}, {"cookedYieldG": "Infinity"}, {"minG": 500, "maxG": 100}, {"maxG": 1201}, {"stepG": 0}, {"source": " "}, {"role": "UNKNOWN"}, {"yieldQuality": "MEASURED"}, {"containsStarch": "false"}]:
            with self.subTest(change=change):
                with self.assertRaises(ValueError):
                    validate_profile(dict(self.profile, **change))

    def test_missing_profile_is_not_silently_treated_as_measured_data(self):
        with self.assertRaises(ValueError):
            validate_profile(None)

class KitchenCatalogueTest(unittest.TestCase):
    def test_new_recipes_cover_all_three_appliances_with_valid_metadata(self):
        recipes=json.loads((Path(__file__).resolve().parents[1]/"data/recipes/kitchen-recipes.json").read_text())
        self.assertEqual(12,len(recipes))
        self.assertEqual(12,len({r["key"] for r in recipes}))
        for code in ["OVEN","MICROWAVE","AIR_FRYER"]:
            self.assertEqual(4,sum(r["equipmentCodes"]==[code] for r in recipes))
        for r in recipes:
            validate_profile(r["portionProfile"]);validate_equipment(r["equipmentCodes"])
            self.assertGreaterEqual(len(r["steps"]),4)
            self.assertEqual(len(r["ingredients"]),len({i["productName"] for i in r["ingredients"]}))
            self.assertTrue(all(i["quantityG"]>0 for i in r["ingredients"]))
    def test_equipment_is_explicit_and_invalid_values_fail_before_import(self):
        validate_equipment([])
        for value in [["OVEN","OVEN"],["UNKNOWN"],None,"OVEN",[{}]]:
            with self.assertRaises(ValueError):validate_equipment(value)

if __name__ == "__main__":
    unittest.main()
