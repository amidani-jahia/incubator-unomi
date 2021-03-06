/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.unomi.plugins.request.actions;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.CityResponse;
import net.sf.uadetector.ReadableUserAgent;
import net.sf.uadetector.UserAgentStringParser;
import net.sf.uadetector.service.UADetectorServiceFactory;
import org.apache.unomi.api.Event;
import org.apache.unomi.api.Session;
import org.apache.unomi.api.actions.Action;
import org.apache.unomi.api.actions.ActionExecutor;
import org.apache.unomi.api.services.EventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class SetRemoteHostInfoAction implements ActionExecutor {
    public static final Pattern IPV4 = Pattern.compile("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}");
    private static final Logger logger = LoggerFactory.getLogger(SetRemoteHostInfoAction.class.getName());
    private DatabaseReader databaseReader;
    private String pathToGeoLocationDatabase;

    public void setPathToGeoLocationDatabase(String pathToGeoLocationDatabase) {
        this.pathToGeoLocationDatabase = pathToGeoLocationDatabase;
    }

    @Override
    public int execute(Action action, Event event) {
        HttpServletRequest httpServletRequest = (HttpServletRequest) event.getAttributes().get(Event.HTTP_REQUEST_ATTRIBUTE);
        if (httpServletRequest == null) {
            return EventService.NO_CHANGE;
        }
        Session session = event.getSession();
        if (session == null) {
            return EventService.NO_CHANGE;
        }

        String remoteAddr = httpServletRequest.getRemoteAddr();
        String remoteAddrParameter = httpServletRequest.getParameter("remoteAddr");
        String xff = httpServletRequest.getHeader("X-Forwarded-For");
        if (remoteAddrParameter != null && remoteAddrParameter.length() > 0) {
            remoteAddr = remoteAddrParameter;
        } else if (xff != null && !xff.equals("")) {
            if (xff.indexOf(',') > -1) {
                xff = xff.substring(0, xff.indexOf(','));
            }
            remoteAddr = xff;
        }

        session.setProperty("remoteAddr", remoteAddr);
        session.setProperty("remoteHost", httpServletRequest.getRemoteHost());
        try {
            if (!remoteAddr.equals("127.0.0.1") && IPV4.matcher(remoteAddr).matches()) {
                ipLookup(remoteAddr, session);
            } else {
                session.setProperty("sessionCountryCode", "CH");
                session.setProperty("sessionCountryName", "Switzerland");
                session.setProperty("sessionCity", "Geneva");
                session.setProperty("sessionAdminSubDiv1", "GE");
                session.setProperty("sessionAdminSubDiv2", "2500");
                session.setProperty("sessionIsp", "Cablecom");
                Map<String, Double> location = new HashMap<String, Double>();
                location.put("lat", 46.1884341);
                location.put("lon", 6.1282508);
                session.setProperty("location", location);
            }
            session.setProperty("countryAndCity", session.getProperty("sessionCountryName") + "@@" + session.getProperty("sessionCity"));
        } catch (Exception e) {
            logger.error("Cannot lookup IP", e);
        }

        UserAgentStringParser parser = UADetectorServiceFactory.getResourceModuleParser();
        ReadableUserAgent agent = parser.parse(httpServletRequest.getHeader("User-Agent"));
        session.setProperty("operatingSystemFamily", agent.getOperatingSystem().getFamilyName());
        session.setProperty("operatingSystemName", agent.getOperatingSystem().getName());
        session.setProperty("userAgentName", agent.getName());
        session.setProperty("userAgentVersion", agent.getVersionNumber().toVersionString());
        session.setProperty("userAgentNameAndVersion", session.getProperty("userAgentName") + "@@" + session.getProperty("userAgentVersion"));
        session.setProperty("deviceCategory", agent.getDeviceCategory().getName());

        return EventService.SESSION_UPDATED;
    }

    private boolean ipLookup(String remoteAddr, Session session) {
        if (databaseReader != null) {
            return ipLookupInDatabase(remoteAddr, session);
        }
        return false;
    }

    @PostConstruct
    public void postConstruct() {
        // A File object pointing to your GeoIP2 or GeoLite2 database
        if (pathToGeoLocationDatabase == null) {
            return;
        }
        File database = new File(pathToGeoLocationDatabase);
        if (!database.exists()) {
            return;
        }

        // This creates the DatabaseReader object, which should be reused across
        // lookups.
        try {
            this.databaseReader = new DatabaseReader.Builder(database).build();
        } catch (IOException e) {
            logger.error("Cannot read IP database", e);
        }

    }

    public boolean ipLookupInDatabase(String remoteAddr, Session session) {
        if (databaseReader == null) {
            return false;
        }

        // Replace "city" with the appropriate method for your database, e.g.,
        // "country".
        CityResponse cityResponse = null;
        try {
            cityResponse = databaseReader.city(InetAddress.getByName(remoteAddr));

            if (cityResponse.getCountry().getName() != null) {
                session.setProperty("sessionCountryCode", cityResponse.getCountry().getIsoCode());
                session.setProperty("sessionCountryName", cityResponse.getCountry().getName());
            }
            if (cityResponse.getCity().getName() != null) {
                session.setProperty("sessionCity", cityResponse.getCity().getName());
                session.setProperty("sessionCityId", cityResponse.getCity().getGeoNameId());
            }

            if (cityResponse.getSubdivisions().size() > 0) {
                session.setProperty("sessionAdminSubDiv1", cityResponse.getSubdivisions().get(0).getGeoNameId());
            }
            if (cityResponse.getSubdivisions().size() > 1) {
                session.setProperty("sessionAdminSubDiv2", cityResponse.getSubdivisions().get(1).getGeoNameId());
            }
            String isp = databaseReader.isp(InetAddress.getByName(remoteAddr)).getIsp();
            if (isp != null) {
                session.setProperty("sessionIsp", isp);
            }

            Map<String, Double> locationMap = new HashMap<String, Double>();
            if (cityResponse.getLocation().getLatitude() != null && cityResponse.getLocation().getLongitude() != null) {
                locationMap.put("lat", cityResponse.getLocation().getLatitude());
                locationMap.put("lon", cityResponse.getLocation().getLongitude());
                session.setProperty("location", locationMap);
            }
            return true;
        } catch (IOException | GeoIp2Exception e) {
            logger.debug("Cannot resolve IP", e);
        }
        return false;
    }
}
