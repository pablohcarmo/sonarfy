package br.com.pablohcarmo.sonarfy.client;

import br.com.pablohcarmo.sonarfy.dto.CityDto;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@Component
public class GeoNamesClient {
    private final RestClient client;
    private final String username;

    public GeoNamesClient(@Value("${geonames.base-url:https://secure.geonames.org}") String baseUrl,
                          @Value("${geonames.username:${GEONAMES_USERNAME:}}") String username) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.username = username;
    }

    // Busca limitada, somente em localidades povoadas e com nomes em português.
    public List<CityDto> search(String query) {
        if (query == null || query.strip().length() < 2 || query.strip().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "City query must contain 2 to 100 characters.");
        }
        JsonNode response = request("/searchJSON", "name_startsWith", query.strip());
        if (response.path("status").path("value").asInt(-1) == 15) return List.of();
        if (response.has("status") || !response.path("geonames").isArray()) throw unavailable();
        List<CityDto> cities = new ArrayList<>();
        for (JsonNode place : response.path("geonames")) {
            cities.add(city(place));
            if (cities.size() == 10) break;
        }
        return cities;
    }

    // O identificador seleciona a cidade exata; país e nome nunca vêm do payload cadastral.
    public CityDto resolve(Long cityId) {
        if (cityId == null || cityId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A positive cityId is required.");
        }
        JsonNode place = request("/getJSON", "geonameId", cityId);
        int status = place.path("status").path("value").asInt(-1);
        if (status == 11 || status == 15) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select an existing city.");
        }
        if (place.has("status") || place.path("fcl").asString("").isEmpty()) throw unavailable();
        if (!"P".equals(place.path("fcl").asString())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select an existing city.");
        }
        CityDto city = city(place);
        if (city.cityId() != cityId) throw unavailable();
        return city;
    }

    private JsonNode request(String path, String parameter, Object value) {
        if (username == null || username.isBlank()) throw unavailable();
        try {
            JsonNode result = client.get().uri(builder -> {
                builder.path(path).queryParam(parameter, value).queryParam("username", username).queryParam("lang", "pt");
                if (path.equals("/searchJSON")) builder.queryParam("maxRows", 10)
                        .queryParam("featureClass", "P").queryParam("orderby", "population");
                return builder.build();
            }).retrieve().body(JsonNode.class);
            if (result == null || !result.isObject()) throw unavailable();
            return result;
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    private CityDto city(JsonNode place) {
        String name = place.path("name").asString("");
        String country = place.path("countryName").asString("");
        if (!place.path("geonameId").isIntegralNumber() || place.path("geonameId").asLong() <= 0
                || !"P".equals(place.path("fcl").asString()) || name.isBlank() || country.isBlank()
                || name.length() > 100 || country.length() > 100) throw unavailable();
        return new CityDto(place.path("geonameId").asLong(), name, place.path("adminName1").asString(""), country);
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "City provider unavailable. Please try again later.");
    }
}
