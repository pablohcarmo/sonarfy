package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.client.GeoNamesClient;
import br.com.pablohcarmo.sonarfy.dto.CityDto;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LocationController {
    private final GeoNamesClient cities;

    public LocationController(GeoNamesClient cities) { this.cities = cities; }

    // Pública para atender também a seleção durante o cadastro.
    @GetMapping("/api/locations/cities")
    public List<CityDto> search(@RequestParam String q) { return cities.search(q); }
}
