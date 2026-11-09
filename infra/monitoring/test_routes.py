import unittest
from generate import extract_routes


class Routes(unittest.TestCase):
    def test_package_private_forecast_controller_is_included(self):
        source='@RequestMapping("/api/forecasts")\nclass ForecastController {\n@GetMapping("/balance")\nObject balance() {}\n@PostMapping("/simulations")\nObject simulate() {}\n}'
        self.assertEqual(extract_routes(source),{'/api/forecasts/balance','/api/forecasts/simulations'})

    def test_public_controller_root_and_parameterized_path(self):
        source='@RequestMapping("/api/cards")\npublic class CardsController {\n@GetMapping\nObject list() {}\n@GetMapping("/{userCardId}")\nObject detail() {}\n}'
        self.assertEqual(extract_routes(source),{'/api/cards','/api/cards/{userCardId}'})

    def test_method_absolute_api_path_without_class_mapping(self):
        self.assertEqual(extract_routes('public final class HealthController {\n@GetMapping("/api/health")\nObject health() {}\n}'),{'/api/health'})


if __name__=='__main__':unittest.main()
