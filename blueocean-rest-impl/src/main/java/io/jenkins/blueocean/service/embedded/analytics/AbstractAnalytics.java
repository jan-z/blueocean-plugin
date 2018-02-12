package io.jenkins.blueocean.service.embedded.analytics;

import com.google.common.base.Objects;
import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;
import hudson.ExtensionList;
import hudson.model.UsageStatistics;
import hudson.model.User;
import hudson.util.ConsistentHash;
import io.jenkins.blueocean.analytics.AdditionalAnalyticsProperties;
import io.jenkins.blueocean.analytics.Analytics;
import io.jenkins.blueocean.commons.ServiceException;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.main.modules.instance_identity.InstanceIdentity;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.Stapler;

import javax.annotation.CheckForNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implements {@link Analytics} to guarantee common properties are tracked with any events sent
 */
@Restricted(NoExternalUse.class)
public abstract class AbstractAnalytics extends Analytics {

    private static final Logger LOGGER = Logger.getLogger(AbstractAnalytics.class.getName());

    public boolean isEnabled() {
        return !UsageStatistics.DISABLED;
    }

    /**
     * @param req to track
     */
    public void track(TrackRequest req) {
        if (req == null) {
            throw new ServiceException.BadRequestException("missing request");
        }
        if (StringUtils.isEmpty(req.name)) {
            throw new ServiceException.BadRequestException("missing name");
        }
        String server = server();
        AnalyticsConfig config = AnalyticsConfigService.get();
        if (!belongsToActiveCohort(config, server)) {
            LOGGER.log(Level.INFO, "Server is not in active cohort. No analytics event has been sent");
        }
        Map<String, Object> allProps = req.properties == null ? Maps.newHashMap() : Maps.newHashMap(req.properties);
        // Enhance with additional properties
        for (AdditionalAnalyticsProperties enhancer : ExtensionList.lookup(AdditionalAnalyticsProperties.class)) {
            Map<String, Object> additionalProperties = enhancer.properties(req);
            if (additionalProperties != null) {
                allProps.putAll(additionalProperties);
            }
        }
        allProps.put("jenkins", server);
        // Background requests do not have userId
        if (Stapler.getCurrentRequest() != null) {
            String identity = identity(server);
            allProps.put("userId", identity);
        }
        Objects.ToStringHelper eventHelper = Objects.toStringHelper(this).add("name", req.name).add("props", allProps);
        try {
            doTrack(req.name, allProps);
            if (LOGGER.isLoggable(Level.FINE)) {
                String msg = eventHelper.toString();
                LOGGER.log(Level.FINE, msg);
            }
        } catch (Throwable throwable) {
            String msg = eventHelper.toString();
            LOGGER.log(Level.WARNING, "Failed to send event: " + msg);
        }
    }

    protected abstract void doTrack(String name, Map<String, Object> allProps);

    protected final String server() {
        byte[] identityBytes;
        try {
            identityBytes = InstanceIdentity.get().getPublic().getEncoded();
        } catch (AssertionError e) {
            LOGGER.log(Level.SEVERE, "There was a problem identifying this server", e);
            throw new IllegalStateException("There was a problem identifying this server", e);
        }
        return Hashing.sha256().hashBytes(identityBytes).toString();
    }

    protected final String identity(String server) {
        User user = User.current();
        String username = user == null ? "ANONYMOUS" : user.getId();
        return Hashing.sha256().hashString(username + server).toString();
    }

    private boolean belongsToActiveCohort(AnalyticsConfig config, String user) {
        if (AnalyticsConfig.EMPTY.equals(config)) {
            return false;
        }
        int currentCohort = Hashing.consistentHash(user.hashCode(), config.cohorts);
        List<Integer> activeCohorts = config.allActiveCohorts();
        return activeCohorts.contains(currentCohort);
    }
}
