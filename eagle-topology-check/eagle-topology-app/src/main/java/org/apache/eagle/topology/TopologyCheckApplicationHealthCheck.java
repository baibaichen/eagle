/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.eagle.topology;

import com.typesafe.config.Config;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.eagle.app.service.impl.ApplicationHealthCheckBase;
import org.apache.eagle.log.entity.GenericServiceAPIResponseEntity;
import org.apache.eagle.metadata.model.ApplicationEntity;
import org.apache.eagle.service.client.IEagleServiceClient;
import org.apache.eagle.service.client.impl.EagleServiceClientImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class TopologyCheckApplicationHealthCheck extends ApplicationHealthCheckBase {
    private static final Logger LOG = LoggerFactory.getLogger(TopologyCheckApplicationHealthCheck.class);
    private static final long DEFAULT_MAX_DELAY_TIME = 10 * 60 * 1000L;

    private TopologyCheckAppConfig topologyCheckAppConfig;

    public TopologyCheckApplicationHealthCheck(Config config) {
        super(config);
        topologyCheckAppConfig = TopologyCheckAppConfig.newInstance(config);
    }

    @Override
    public Result check() {
        //FIXME, this application owner please add eagle server config to Class TopologyCheckAppConfig
        IEagleServiceClient client = new EagleServiceClientImpl(
                topologyCheckAppConfig.getConfig().getString("service.host"),
                topologyCheckAppConfig.getConfig().getInt("service.port"),
                topologyCheckAppConfig.getConfig().getString("service.username"),
                topologyCheckAppConfig.getConfig().getString("service.password"));

        client.setReadTimeout(topologyCheckAppConfig.getConfig().getInt("service.readTimeOutSeconds") * 1000);

        String message = "";
        try {
            ApplicationEntity.Status status = getApplicationStatus();
            if (!status.toString().equals(ApplicationEntity.Status.RUNNING.toString())) {
                message += String.format("Application is not RUNNING, status is %s. ", status.toString());
            }

            long currentProcessTimeStamp = Math.min(
                    Math.min(
                        getServiceLatestUpdateTime(TopologyConstants.HBASE_INSTANCE_SERVICE_NAME, client),
                        getServiceLatestUpdateTime(TopologyConstants.HDFS_INSTANCE_SERVICE_NAME, client)
                    ), getServiceLatestUpdateTime(TopologyConstants.MR_INSTANCE_SERVICE_NAME, client));
            long currentTimeStamp = System.currentTimeMillis();
            long maxDelayTime = DEFAULT_MAX_DELAY_TIME;
            if (topologyCheckAppConfig.getConfig().hasPath(MAX_DELAY_TIME_KEY)) {
                maxDelayTime = topologyCheckAppConfig.getConfig().getLong(MAX_DELAY_TIME_KEY);
            }

            if (!message.isEmpty() || currentTimeStamp - currentProcessTimeStamp > maxDelayTime) {
                message += String.format("Current process time is %sms, delay %s.",
                        currentProcessTimeStamp, formatMillSeconds(currentTimeStamp - currentProcessTimeStamp));
                return Result.unhealthy(message);
            } else {
                return Result.healthy();
            }
        } catch (Exception e) {
            return Result.unhealthy(printMessages(message, "An exception was caught when fetch application current process time: ", ExceptionUtils.getStackTrace(e)));
        } finally {
            try {
                client.close();
            } catch (Exception e) {
                LOG.warn("{}", e);
            }
        }
    }

    private long getServiceLatestUpdateTime(String serviceName, IEagleServiceClient client) throws Exception {
        String query = String.format("%s[@site=\"%s\"]<@site>{max(lastUpdateTime)}",
                serviceName,
                topologyCheckAppConfig.dataExtractorConfig.site);

        GenericServiceAPIResponseEntity response = client
                .search(query)
                .pageSize(10)
                .send();

        List<Map<List<String>, List<Double>>> results = response.getObj();
        if (results.size() == 0) {
            return Long.MAX_VALUE;
        }
        long currentProcessTimeStamp = results.get(0).get("value").get(0).longValue();
        return currentProcessTimeStamp;
    }
}
