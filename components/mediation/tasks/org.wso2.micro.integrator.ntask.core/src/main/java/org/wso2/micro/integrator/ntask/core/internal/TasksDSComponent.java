/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.micro.integrator.ntask.core.internal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.quartz.Scheduler;
import org.quartz.impl.StdSchedulerFactory;
import org.wso2.carbon.securevault.SecretCallbackHandlerService;
import org.wso2.micro.core.ServerStartupObserver;
import org.wso2.micro.integrator.coordination.ClusterCoordinator;
import org.wso2.micro.integrator.coordination.util.RDBMSConstantUtils;
import org.wso2.micro.integrator.core.util.MicroIntegratorBaseUtils;
import org.wso2.micro.integrator.ndatasource.common.DataSourceException;
import org.wso2.micro.integrator.ndatasource.core.CarbonDataSource;
import org.wso2.micro.integrator.ndatasource.core.DataSourceService;
import org.wso2.micro.integrator.ntask.core.TaskStartupHandler;
import org.wso2.micro.integrator.ntask.core.impl.QuartzCachedThreadPool;
import org.wso2.micro.integrator.ntask.core.service.TaskService;
import org.wso2.micro.integrator.ntask.core.service.impl.TaskServiceImpl;

import java.io.File;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;

/**
 * This class represents the Tasks declarative service component.
 */
@Component(name = "org.wso2.micro.integrator.ntask.core.internal.TasksDSComponent",
        immediate = true)
public class TasksDSComponent {

    private static final String QUARTZ_PROPERTIES_FILE_NAME = "quartz.properties";

    private final Log log = LogFactory.getLog(TasksDSComponent.class);

    private static Scheduler scheduler;

    private static SecretCallbackHandlerService secretCallbackHandlerService;

    private static TaskService taskService;

    private static ExecutorService executor = Executors.newCachedThreadPool();

    private static DataSourceService dataSourceService;
    private Object coordinationDatasourceObject;
    private boolean coordinationEnabled;

    @Activate
    protected void activate(ComponentContext ctx) {

        try {
            if (executor.isShutdown()) {
                executor = Executors.newCachedThreadPool();
            }
            String quartzConfigFilePath =
                    MicroIntegratorBaseUtils.getCarbonConfigDirPath() + File.separator + "etc" + File.separator
                            + QUARTZ_PROPERTIES_FILE_NAME;
            StdSchedulerFactory fac;
            if (new File(quartzConfigFilePath).exists()) {
                fac = new StdSchedulerFactory(quartzConfigFilePath);
            } else {
                fac = new StdSchedulerFactory(this.getStandardQuartzProps());
            }
            TasksDSComponent.scheduler = fac.getScheduler();
            TasksDSComponent.getScheduler().start();

            coordinationEnabled = isTaskDataSourceAvailable();
            if (coordinationEnabled) {
                log.info("Initializing  RDBMS coordination");
                DataSource coordinationDataSource = (DataSource) coordinationDatasourceObject;
                ClusterCoordinator clusterCoordinator = new ClusterCoordinator(coordinationDataSource);
                clusterCoordinator.startCoordinator();
            }

            if (getTaskService() == null) {
                taskService = new TaskServiceImpl();
            }

            BundleContext bundleContext = ctx.getBundleContext();
            bundleContext
                    .registerService(ServerStartupObserver.class.getName(), new TaskStartupHandler(taskService), null);
            bundleContext.registerService(TaskService.class.getName(), getTaskService(), null);

        } catch (Throwable e) {
            log.error("Error in intializing Tasks component: " + e.getMessage(), e);
        }
    }

    /**
     * Check whether the WSO2_COORDINATION_DB is defined.
     *
     * @return - true if datasource defined.
     * @throws DataSourceException
     */
    private boolean isTaskDataSourceAvailable() throws DataSourceException {

        CarbonDataSource dataSource = dataSourceService.getDataSource(RDBMSConstantUtils.COORDINATION_DB_NAME);
        if (dataSource == null) {
            return false;
        }
        coordinationDatasourceObject = dataSource.getDSObject();
        if (!(coordinationDatasourceObject instanceof DataSource)) {
            throw new DataSourceException("DataSource is not an RDBMS data source.");
        }
        return true;
    }

    private Properties getStandardQuartzProps() {

        Properties result = new Properties();
        result.put("org.quartz.scheduler.skipUpdateCheck", "true");
        result.put("org.quartz.threadPool.class", QuartzCachedThreadPool.class.getName());
        return result;
    }

    @Deactivate
    protected void deactivate(ComponentContext ctx) {

        if (TasksDSComponent.getScheduler() != null) {
            try {
                TasksDSComponent.getScheduler().shutdown();
            } catch (Exception e) {
                log.error(e);
            }
        }
        executor.shutdown();
        taskService = null;
    }

    public static TaskService getTaskService() {

        return taskService;
    }

    public static Scheduler getScheduler() {

        return scheduler;
    }

    public static SecretCallbackHandlerService getSecretCallbackHandlerService() {

        return TasksDSComponent.secretCallbackHandlerService;
    }

    @Reference(name = "secret.callback.handler.service",
            service = org.wso2.carbon.securevault.SecretCallbackHandlerService.class,
            cardinality = ReferenceCardinality.MANDATORY,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "unsetSecretCallbackHandlerService")
    protected void setSecretCallbackHandlerService(SecretCallbackHandlerService secretCallbackHandlerService) {

        TasksDSComponent.secretCallbackHandlerService = secretCallbackHandlerService;
    }

    protected void unsetSecretCallbackHandlerService(SecretCallbackHandlerService secretCallbackHandlerService) {

        TasksDSComponent.secretCallbackHandlerService = null;
    }

    @Reference(name = "org.wso2.carbon.ndatasource",
               service = DataSourceService.class,
               cardinality = ReferenceCardinality.MANDATORY,
               policy = ReferencePolicy.DYNAMIC,
               unbind = "unsetDatasourceHandlerService")
    protected void setDatasourceHandlerService(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    protected void unsetDatasourceHandlerService(DataSourceService dataSourceService) {
        this.dataSourceService = null;
    }

}
