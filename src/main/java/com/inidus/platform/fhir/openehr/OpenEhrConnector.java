package com.inidus.platform.fhir.openehr;

import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.param.TokenParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

/**
 * Connects to an openEHR backend and returns selected data
 */
@ConfigurationProperties(prefix = "cdr-connector", ignoreUnknownFields = false)
@Service
public abstract class OpenEhrConnector {
    protected static final DateFormat ISO_DATE = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'"); // Quoted "Z" to indicate UTC, no timezone offset
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private String url;
    private String username;
    private String password;
    private boolean isTokenAuth;

    {
        ISO_DATE.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public JsonNode getAllResources() throws IOException {
        return getEhrJson(getAQL());
    }

    protected abstract String getAQL();

    public JsonNode getResourceById(String id) throws IOException {
        if (null == id || id.isEmpty() || id.contains(" ")) {
            return null;
        }

        // Test for presence of entryId as well as compositionId
        // delineated by '_' character
        // If entryID exists query on compositionId and entryId.

        String[] openEHRIds = id.split("\\|");
        String compositionId = openEHRIds[0];

        String idFilter = " and a/uid/value='" + compositionId + "'";

        if (openEHRIds.length > 1) {
            String entryId = openEHRIds[1];
            idFilter.concat(" and b_a/uid/value='" + entryId + "'");
        }
        return getEhrJson(getAQL() + idFilter);
    }

    protected JsonNode getEhrJson(String aql) throws IOException {
        MultiValueMap<String, String> headers;
        if (isTokenAuth) {
            headers = createTokenHeaders();
        } else {
            headers = createAuthHeaders();
        }

        logger.debug("AQL:  " + aql);

        String body = "{\"aql\" : \"" + aql + "\"}";
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        String url = this.url + "/rest/v1/query";

        ResponseEntity<String> result = new RestTemplate().exchange(url, HttpMethod.POST, request, String.class);

        if (isTokenAuth) {
            deleteSessionToken(headers);
        }

        if (result.getStatusCode() == HttpStatus.OK) {
            JsonNode resultJson = new ObjectMapper().readTree(result.getBody());
            return resultJson.get("resultSet");
        } else {
            return null;
        }
    }

    protected String getPatientIdentifierFilterAql(TokenParam patientIdentifier) {
        String system = patientIdentifier.getSystem();
        if (system.isEmpty() || "https://fhir.nhs.uk/Id/nhs-number".equals(system)) {
            system = "uk.nhs.nhs_number";
        }
        String idFilter = " and e/ehr_status/subject/external_ref/id/value='" + patientIdentifier.getValue() +
                "' and e/ehr_status/subject/external_ref/namespace='" + system + "'";
        return idFilter;
    }

    protected String getPatientIdFilterAql(StringParam patientId) {

        String idFilter = " and e/ehr_id/value='" + patientId.getValue() + "'";

        return idFilter;
    }

    private HttpHeaders createAuthHeaders() {
        String plainCredits = username + ":" + password;
        String auth = "Basic " + new String(Base64.encodeBase64(plainCredits.getBytes()));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Authorization", auth);
        return headers;
    }

    private HttpHeaders createTokenHeaders() throws IOException {
        String sessionToken = getSessionToken(username, password, url + "/rest/v1/session");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Ehr-Session", sessionToken);
        return headers;
    }

    private String getSessionToken(String userName, String userPassword, String url) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>("", headers);

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url)
                .queryParam("username", userName)
                .queryParam("password", userPassword);

        ResponseEntity<String> result = new RestTemplate().exchange(
                builder.build().encode().toUri(),
                HttpMethod.POST,
                request,
                String.class);

        JsonNode resultJson = new ObjectMapper().readTree(result.getBody());
        return resultJson.get("sessionId").asText();
    }

    private void deleteSessionToken(MultiValueMap<String, String> headers) {
        HttpEntity<String> request = new HttpEntity<>("", headers);

        String url = this.url + "/rest/v1/session";
        ResponseEntity<String> result = new RestTemplate().exchange(url, HttpMethod.DELETE, request, String.class);

        JsonNode resultJson = null;
        try {
            resultJson = new ObjectMapper().readTree(result.getBody());
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Should return "DELETE"
        String action = resultJson.asText("action");
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setIsTokenAuth(boolean tokenAuth) {
        isTokenAuth = tokenAuth;
    }
}