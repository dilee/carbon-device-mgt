/*
 * Copyright (c) 2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.device.mgt.core.service;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.device.mgt.common.*;
import org.wso2.carbon.device.mgt.common.configuration.mgt.TenantConfiguration;
import org.wso2.carbon.device.mgt.common.license.mgt.License;
import org.wso2.carbon.device.mgt.common.license.mgt.LicenseManagementException;
import org.wso2.carbon.device.mgt.common.operation.mgt.Operation;
import org.wso2.carbon.device.mgt.common.operation.mgt.OperationManagementException;
import org.wso2.carbon.device.mgt.common.spi.DeviceManagementService;
import org.wso2.carbon.device.mgt.core.DeviceManagementPluginRepository;
import org.wso2.carbon.device.mgt.core.config.DeviceConfigurationManager;
import org.wso2.carbon.device.mgt.core.config.email.EmailConfigurations;
import org.wso2.carbon.device.mgt.core.config.email.NotificationMessages;
import org.wso2.carbon.device.mgt.core.dao.*;
import org.wso2.carbon.device.mgt.core.dto.DeviceType;
import org.wso2.carbon.device.mgt.core.email.EmailConstants;
import org.wso2.carbon.device.mgt.core.internal.DeviceManagementDataHolder;
import org.wso2.carbon.device.mgt.core.internal.DeviceManagementServiceComponent;
import org.wso2.carbon.device.mgt.core.internal.EmailServiceDataHolder;
import org.wso2.carbon.device.mgt.core.internal.PluginInitializationListener;
import org.wso2.carbon.user.api.UserStoreException;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class DeviceManagementProviderServiceImpl implements DeviceManagementProviderService,
        PluginInitializationListener {

    private DeviceDAO deviceDAO;
    private DeviceTypeDAO deviceTypeDAO;
    private EnrollmentDAO enrollmentDAO;
    private DeviceManagementPluginRepository pluginRepository;

    private static Log log = LogFactory.getLog(DeviceManagementProviderServiceImpl.class);

    public DeviceManagementProviderServiceImpl() {
        this.pluginRepository = new DeviceManagementPluginRepository();
        initDataAccessObjects();
        /* Registering a listener to retrieve events when some device management service plugin is installed after
        * the component is done getting initialized */
        DeviceManagementServiceComponent.registerPluginInitializationListener(this);
    }

    private void initDataAccessObjects() {
        this.deviceDAO = DeviceManagementDAOFactory.getDeviceDAO();
        this.deviceTypeDAO = DeviceManagementDAOFactory.getDeviceTypeDAO();
        this.enrollmentDAO = DeviceManagementDAOFactory.getEnrollmentDAO();
    }

    @Override
    public boolean saveConfiguration(TenantConfiguration configuration) throws DeviceManagementException {
        DeviceManager dms =
                this.getPluginRepository().getDeviceManagementService(configuration.getType()).getDeviceManager();
        return dms.saveConfiguration(configuration);
    }

    @Override
    public TenantConfiguration getConfiguration() throws DeviceManagementException {
        return null;
    }

    @Override
    public TenantConfiguration getConfiguration(String deviceType) throws DeviceManagementException {
        DeviceManager dms =
                this.getPluginRepository().getDeviceManagementService(deviceType).getDeviceManager();
        if (dms == null) {
            if (log.isDebugEnabled()) {
                log.debug("Device type '" + deviceType + "' does not have an associated device management " +
                        "plugin registered within the framework. Therefore, not attempting getConfiguration method");
            }
            return null;
        }
        return dms.getConfiguration();
    }

    @Override
    public FeatureManager getFeatureManager(String deviceType) {
        DeviceManager deviceManager = this.getDeviceManager(deviceType);
        if (deviceManager == null) {
            if (log.isDebugEnabled()) {
                log.debug("Device Manager associated with the device type '" + deviceType + "' is null. " +
                        "Therefore, not attempting method 'getFeatureManager'");
            }
            return null;
        }
        return deviceManager.getFeatureManager();
    }

    @Override
    public boolean enrollDevice(Device device) throws DeviceManagementException {
        boolean status = false;
        DeviceIdentifier deviceIdentifier = new DeviceIdentifier(device.getDeviceIdentifier(), device.getType());

        DeviceManager deviceManager = this.getDeviceManager(device.getType());
        if (deviceManager == null) {
            if (log.isDebugEnabled()) {
                log.debug("Device Manager associated with the device type '" + device.getType() + "' is null. " +
                        "Therefore, not attempting method 'enrollDevice'");
            }
            return false;
        }
        deviceManager.enrollDevice(device);

        if (deviceManager.isClaimable(deviceIdentifier)) {
            device.getEnrolmentInfo().setStatus(EnrolmentInfo.Status.INACTIVE);
        } else {
            device.getEnrolmentInfo().setStatus(EnrolmentInfo.Status.ACTIVE);
        }
        int tenantId = this.getTenantId();

        Device existingDevice = this.getDevice(deviceIdentifier);

        if (existingDevice != null) {
            EnrolmentInfo existingEnrolmentInfo = existingDevice.getEnrolmentInfo();
            EnrolmentInfo newEnrolmentInfo = device.getEnrolmentInfo();
            if (existingEnrolmentInfo != null && newEnrolmentInfo != null) {
                //Get all the enrollments of current user for the same device
                List<EnrolmentInfo> enrolmentInfos = this.getEnrollmentsOfUser(existingDevice.getId(), newEnrolmentInfo.getOwner());
                for (EnrolmentInfo enrolmentInfo : enrolmentInfos) {
                    //If the enrollments are same then we'll update the existing enrollment.
                    if (enrolmentInfo.equals(newEnrolmentInfo)) {
                        device.setId(existingDevice.getId());
                        device.getEnrolmentInfo().setDateOfEnrolment(enrolmentInfo.getDateOfEnrolment());
                        device.getEnrolmentInfo().setId(enrolmentInfo.getId());
                        this.modifyEnrollment(device);
                        status = true;
                        break;
                    }
                }
                if (!status) {
                    int enrolmentId, updateStatus = 0;
                    try {
                        //Remove the existing enrollment
                        DeviceManagementDAOFactory.beginTransaction();
                        if (!EnrolmentInfo.Status.REMOVED.equals(existingEnrolmentInfo.getStatus())) {
                            existingEnrolmentInfo.setStatus(EnrolmentInfo.Status.REMOVED);
                            updateStatus = enrollmentDAO.updateEnrollment(existingEnrolmentInfo);
                        }
                        if ((updateStatus > 0) || EnrolmentInfo.Status.REMOVED.equals(existingEnrolmentInfo.getStatus())) {
                            enrolmentId = enrollmentDAO.addEnrollment(existingDevice.getId(), newEnrolmentInfo, tenantId);
                            DeviceManagementDAOFactory.commitTransaction();
                            if (log.isDebugEnabled()) {
                                log.debug("An enrolment is successfully added with the id '" + enrolmentId +
                                          "' associated with " + "the device identified by key '" +
                                          device.getDeviceIdentifier() + "', which belongs to " + "platform '" +
                                          device.getType() + " upon the user '" + device.getEnrolmentInfo().getOwner() + "'");
                            }
                            status = true;
                        } else {
                            log.warn("Unable to update device enrollment for device : " + device.getDeviceIdentifier() +
                                     " belonging to user : " + device.getEnrolmentInfo().getOwner());
                        }
                    } catch (DeviceManagementDAOException e) {
                        DeviceManagementDAOFactory.rollbackTransaction();
                        throw new DeviceManagementException("Error occurred while adding enrolment related metadata", e);
                    } catch (TransactionManagementException e) {
                        throw new DeviceManagementException("Error occurred while initiating transaction", e);
                    } finally {
                        DeviceManagementDAOFactory.closeConnection();
                    }
                }
            }
        } else {
            int enrolmentId = 0;
            try {
                DeviceManagementDAOFactory.beginTransaction();
                DeviceType type = deviceTypeDAO.getDeviceType(device.getType());
                int deviceId = deviceDAO.addDevice(type.getId(), device, tenantId);
                enrolmentId = enrollmentDAO.addEnrollment(deviceId, device.getEnrolmentInfo(), tenantId);
                DeviceManagementDAOFactory.commitTransaction();
            } catch (DeviceManagementDAOException e) {
                DeviceManagementDAOFactory.rollbackTransaction();
                throw new DeviceManagementException("Error occurred while adding metadata of '" + device.getType() +
                        "' device carrying the identifier '" + device.getDeviceIdentifier() + "'", e);
            } catch (TransactionManagementException e) {
                throw new DeviceManagementException("Error occurred while initiating transaction", e);
            } finally {
                DeviceManagementDAOFactory.closeConnection();
            }

            if (log.isDebugEnabled()) {
                log.debug("An enrolment is successfully created with the id '" + enrolmentId + "' associated with " +
                        "the device identified by key '" + device.getDeviceIdentifier() + "', which belongs to " +
                        "platform '" + device.getType() + " upon the user '" +
                        device.getEnrolmentInfo().getOwner() + "'");
            }
            status = true;
        }

        return status;
    }


    @Override
    public boolean modifyEnrollment(Device device) throws DeviceManagementException {
        DeviceManager deviceManager = this.getDeviceManager(device.getType());
        if (deviceManager == null) {
            if (log.isDebugEnabled()) {
                log.debug("Device Manager associated with the device type '" + device.getType() + "' is null. " +
                        "Therefore, not attempting method 'modifyEnrolment'");
            }
            return false;
        }
        boolean status = deviceManager.modifyEnrollment(device);
        try {
            int tenantId = this.getTenantId();
            DeviceManagementDAOFactory.beginTransaction();

            DeviceType type = deviceTypeDAO.getDeviceType(device.getType());
            deviceDAO.updateDevice(type.getId(), device, tenantId);
            enrollmentDAO.updateEnrollment(device.getEnrolmentInfo());

            DeviceManagementDAOFactory.commitTransaction();
        } catch (DeviceManagementDAOException e) {
            DeviceManagementDAOFactory.rollbackTransaction();
            throw new DeviceManagementException("Error occurred while modifying the device " +
                    "'" + device.getId() + "'", e);
        } catch (TransactionManagementException e) {
            throw new DeviceManagementException("Error occurred while initiating transaction", e);
        } finally {
            DeviceManagementDAOFactory.closeConnection();
        }
        return status;
    }

    private List<EnrolmentInfo> getEnrollmentsOfUser(int deviceId, String user)
            throws DeviceManagementException {
        List<EnrolmentInfo> enrolmentInfos = new ArrayList<>();
        try {
            DeviceManagementDAOFactory.openConnection();
            enrolmentInfos = enrollmentDAO.getEnrollmentsOfUser(deviceId, user, this.getTenantId());
        } catch (DeviceManagementDAOException e) {
            throw new DeviceManagementException("Error occurred while obtaining the enrollment information device for id " +
                                                "'" + deviceId + "' and user : " + user, e);
        } catch (SQLException e) {
            throw new DeviceManagementException("Error occurred while opening a connection to the data source", e);
        } finally {
            DeviceManagementDAOFactory.closeConnection();
        }
        return enrolmentInfos;
    }

    @Override
    public boolean disenrollDevice(DeviceIdentifier deviceId) throws DeviceManagementException {
        DeviceManager deviceManager = this.getDeviceManager(deviceId.getType());
        if (deviceManager == null) {
            if (log.isDebugEnabled()) {
                log.debug("Device Manager associated with the device type '" + deviceId.getType() + "' is null. " +
                        "Therefore, not attempting method 'dis-enrollDevice'");
            }
            return false;
        }
        try {
            int tenantId = this.getTenantId();
            DeviceManagementDAOFactory.beginTransaction();

            Device device = deviceDAO.getDevice(deviceId, tenantId);
            if (device == null) {
                if (log.isDebugEnabled()) {
                    log.debug("Device not found for id '" + deviceId.getId() + "'");
                }
                throw new DeviceManagementException("Device not found");
            }
            DeviceType deviceType = deviceTypeDAO.getDeviceType(device.getType());

            device.getEnrolmentInfo().setDateOfLastUpdate(new Date().getTime());
            device.getEnrolmentInfo().setStatus(EnrolmentInfo.Status.REMOVED);
            enrollmentDAO.updateEnrollment(device.getId(), device.getEnrolmentInfo(), tenantId);
            deviceDAO.updateDevice(deviceType.getId(), device, tenantId);

            DeviceManagementDAOFactory.commitTransaction();
        } catch (DeviceManagementDAOException e) {
            DeviceManagementDAOFactory.rollbackTransaction();
            throw new DeviceManagementException("Error occurred while dis-enrolling '" + deviceId.getType() +
                    "' device with the identifier '" + deviceId.getId() + "'", e);
        } catch (TransactionManagementException e) {
            throw new DeviceManagementException("Error occurred while initiating transaction", e);
        } finally {
            DeviceManagementDAOFactory.closeConnection();
        }
        return deviceManager.disenrollDevice(deviceId);
    }

    @Override
    public boolean isEnrolled(DeviceIdentifier deviceId) throws DeviceManagementException {
        DeviceManager deviceManager = this.getDeviceManager(deviceId.getType());
        if (deviceManager == null) {
            if (log.isDebugEnabled()) {
                log.debug("Device Manager associated with the device type '" + deviceId.getType() + "' is null. " +
                        "Therefore, not attempting method 'isEnrolled'");
            }
            return false;
        }
        return deviceManager.isEnrolled(deviceId);
    }

    @Override
    public boolean isActive(DeviceIdentifier deviceId) throws DeviceManagementException {
        DeviceManager deviceManager = this.getDeviceManager(deviceId.getType());
        if (deviceManager == null) {
            if (log.isDebugEnabled()) {
                log.debug("Device Manager associated with the device type '" + deviceId.getType() + "' is null. " +
                        "Therefore, not attempting method 'isActive'");
            }
            return false;
        }
        return deviceManager.isActive(deviceId);
    }

    @Override
    public boolean setActive(DeviceIdentifier deviceId, boolean status) throws DeviceManagementException {
        DeviceManager deviceManager = this.getDeviceManager(deviceId.getType());
        if (deviceManager == null) {
            if (log.isDebugEnabled()) {
                log.debug("Device Manager associated with the device type '" + deviceId.getType() + "' is null. " +
                        "Therefore, not attempting method 'setActive'");
            }
            return false;
        }
        return deviceManager.setActive(deviceId, status);
    }

    @Override
    public List<Device> getAllDevices() throws DeviceManagementException {
        List<Device> devices = new ArrayList<>();
        List<Device> allDevices;
        try {
            DeviceManagementDAOFactory.openConnection();
            allDevices = deviceDAO.getDevices(this.getTenantId());
        } catch (DeviceManagementDAOException e) {
            throw new DeviceManagementException("Error occurred while retrieving device list pertaining to " +
                    "the current tenant", e);
        } catch (SQLException e) {
            throw new DeviceManagementException("Error occurred while opening a connection to the data source", e);
        } finally {
            DeviceManagementDAOFactory.closeConnection();
        }

        for (Device device : allDevices) {
            DeviceManager deviceManager = this.getDeviceManager(device.getType());
            if (deviceManager == null) {
                if (log.isDebugEnabled()) {
                    log.debug("Device Manager associated with the device type '" + device.getType() + "' is null. " +
                            "Therefore, not attempting method 'isEnrolled'");
                }
                devices.add(device);
                continue;
            }
            Device dmsDevice =
                    deviceManager.getDevice(new DeviceIdentifier(device.getDeviceIdentifier(), device.getType()));
            if (dmsDevice != null) {
                device.setFeatures(dmsDevice.getFeatures());
                device.setProperties(dmsDevice.getProperties());
            }
            devices.add(device);
        }
        return devices;
    }

    @Override
    public PaginationResult getAllDevices(String deviceType, int index, int limit) throws DeviceManagementException {
        PaginationResult paginationResult;
        List<Device> devices = new ArrayList<>();
        List<Device> allDevices;
        try {
            DeviceManagementDAOFactory.openConnection();
            paginationResult = deviceDAO.getDevices(deviceType, index, limit, this.getTenantId());
        } catch (DeviceManagementDAOException e) {
            throw new DeviceManagementException("Error occurred while retrieving device list pertaining to " +
                                                "the current tenant", e);
        } catch (SQLException e) {
            throw new DeviceManagementException("Error occurred while opening a connection to the data source", e);
        } finally {
            DeviceManagementDAOFactory.closeConnection();
        }
        allDevices = (List<Device>) paginationResult.getData();
        for (Device device : allDevices) {
            DeviceManager deviceManager = this.getDeviceManager(device.getType());
            if (deviceManager == null) {
                if (log.isDebugEnabled()) {
                    log.debug("Device Manager associated with the device type '" + device.getType() + "' is null. " +
                              "Therefore, not attempting method 'isEnrolled'");
                }
                devices.add(device);
                continue;
            }
            Device dmsDevice =
                    deviceManager.getDevice(new DeviceIdentifier(device.getDeviceIdentifier(), device.getType()));
            if (dmsDevice != null) {
                device.setFeatures(dmsDevice.getFeatures());
                device.setProperties(dmsDevice.getProperties());
            }
            devices.add(device);
        }
        paginationResult.setData(devices);
        return paginationResult;
    }

    @Override
    public PaginationResult getAllDevices(int index, int limit) throws DeviceManagementException {
        PaginationResult paginationResult;
        List<Device> devices = new ArrayList<>();
        List<Device> allDevices;
        try {
            DeviceManagementDAOFactory.openConnection();
            paginationResult = deviceDAO.getDevices(index, limit, this.getTenantId());
        } catch (DeviceManagementDAOException e) {
            throw new DeviceManagementException("Error occurred while retrieving device list pertaining to " +
                                                "the current tenant", e);
        } catch (SQLException e) {
            throw new DeviceManagementException("Error occurred while opening a connection to the data source", e);
        } finally {
            DeviceManagementDAOFactory.closeConnection();
        }
        allDevices = (List<Device>) paginationResult.getData();
        for (Device device : allDevices) {
            DeviceManager deviceManager = this.getDeviceManager(device.getType());
            if (deviceManager == null) {
                if (log.isDebugEnabled()) {
                    log.debug("Device Manager associated with the device type '" + device.getType() + "' is null. " +
                              "Therefore, not attempting method 'isEnrolled'");
                }
                devices.add(device);
                continue;
            }
            Device dmsDevice =
                    deviceManager.getDevice(new DeviceIdentifier(device.getDeviceIdentifier(), device.getType()));
            if (dmsDevice != null) {
                device.setFeatures(dmsDevice.getFeatures());
                device.setProperties(dmsDevice.getProperties());
            }
            devices.add(device);
        }
        paginationResult.setData(devices);
        return paginationResult;
    }

    @Override
    public List<Device> getAllDevices(String deviceType) throws DeviceManagementException {
        List<Device> devices = new ArrayList<>();
        List<Device> allDevices;
        try {
            DeviceManagementDAOFactory.openConnection();
            allDevices = deviceDAO.getDevices(deviceType, this.getTenantId());
        } catch (DeviceManagementDAOException e) {
            throw new DeviceManagementException("Error occurred while retrieving all devices of type '" +
                    deviceType + "' that are being managed within the scope of current tenant", e);
        } catch (SQLException e) {
            throw new DeviceManagementException("Error occurred while opening a connection to the data source", e);
        } finally {
            DeviceManagementDAOFactory.closeConnection();
        }

        for (Device device : allDevices) {
            DeviceManager deviceManager = this.getDeviceManager(deviceType);
            if (deviceManager == null) {
                if (log.isDebugEnabled()) {
                    log.debug("Device Manager associated with the device type '" + deviceType + "' is null. " +
                            "Therefore, not attempting method 'isEnrolled'");
                }
                devices.add(device);
                continue;
            }
            Device dmsDevice =
                    deviceManager.getDevice(new DeviceIdentifier(device.getDeviceIdentifier(), device.getType()));
            if (dmsDevice != null) {
                device.setFeatures(dmsDevice.getFeatures());
                device.setProperties(dmsDevice.getProperties());
            }
            devices.add(device);
        }
        return devices;
    }

    @Override
    public void sendEnrolmentInvitation(EmailMessageProperties emailMessageProperties)
            throws DeviceManagementException {
        List<NotificationMessages> notificationMessages =
                DeviceConfigurationManager.getInstance().getNotificationMessagesConfig().getNotificationMessagesList();
        String messageHeader = "";
        String messageBody = "";
        String messageFooter1 = "";
        String messageFooter2 = "";
        String messageFooter3 = "";
        String url = "";
        String subject = "";

        for (NotificationMessages notificationMessage : notificationMessages) {
            if (org.wso2.carbon.device.mgt.core.DeviceManagementConstants.EmailNotifications.ENROL_NOTIFICATION_TYPE
                    .equals(
                            notificationMessage.getType())) {
                messageHeader = notificationMessage.getHeader();
                messageBody = notificationMessage.getBody();
                messageFooter1 = notificationMessage.getFooterLine1();
                messageFooter2 = notificationMessage.getFooterLine2();
                messageFooter3 = notificationMessage.getFooterLine3();
                url = notificationMessage.getUrl();
                subject = notificationMessage.getSubject();
                break;
            }
        }

        StringBuilder messageBuilder = new StringBuilder();

        try {

            // Reading the download url from the cdm-config.xml file
            EmailConfigurations emailConfig =
                    DeviceConfigurationManager.getInstance().getDeviceManagementConfig().
                            getDeviceManagementConfigRepository().getEmailConfigurations();
            emailMessageProperties.setEnrolmentUrl(emailConfig.getlBHostPortPrefix()+ emailConfig.getEnrollmentContextPath());

            messageHeader = messageHeader.replaceAll("\\{" + EmailConstants.EnrolmentEmailConstants.FIRST_NAME + "\\}",
                    URLEncoder.encode(emailMessageProperties.getFirstName(),
                            EmailConstants.EnrolmentEmailConstants.ENCODED_SCHEME));
            messageBody = messageBody.trim() + System.getProperty("line.separator") +
                    System.getProperty("line.separator") + url.replaceAll("\\{"
                            + EmailConstants.EnrolmentEmailConstants.DOWNLOAD_URL + "\\}",
                    URLDecoder.decode(emailMessageProperties.getEnrolmentUrl(),
                            EmailConstants.EnrolmentEmailConstants.ENCODED_SCHEME));

            messageBuilder.append(messageHeader).append(System.getProperty("line.separator"))
                    .append(System.getProperty("line.separator"));
            messageBuilder.append(messageBody);
            messageBuilder.append(System.getProperty("line.separator")).append(System.getProperty("line.separator"));
            messageBuilder.append(messageFooter1.trim())
                    .append(System.getProperty("line.separator")).append(messageFooter2.trim()).append(System
                    .getProperty("line.separator")).append(messageFooter3.trim());

        } catch (IOException e) {
            throw new DeviceManagementException("Error replacing tags in email template '" +
                    emailMessageProperties.getSubject() + "'", e);
        }
        emailMessageProperties.setMessageBody(messageBuilder.toString());
        emailMessageProperties.setSubject(subject);
        EmailServiceDataHolder.getInstance().getEmailServiceProvider().sendEmail(emailMessageProperties);
    }

    @Override
    public void sendRegistrationEmail(EmailMessageProperties emailMessageProperties) throws DeviceManagementException {
        List<NotificationMessages> notificationMessages =
                DeviceConfigurationManager.getInstance().getNotificationMessagesConfig().getNotificationMessagesList();
        String messageHeader = "";
        String messageBody = "";
        String messageFooter1 = "";
        String messageFooter2 = "";
        String messageFooter3 = "";
        String url = "";
        String subject = "";

        for (NotificationMessages notificationMessage : notificationMessages) {
            if (org.wso2.carbon.device.mgt.core.DeviceManagementConstants.EmailNotifications.USER_REGISTRATION_NOTIFICATION_TYPE.
                    equals(notificationMessage.getType())) {
                messageHeader = notificationMessage.getHeader();
                messageBody = notificationMessage.getBody();
                messageFooter1 = notificationMessage.getFooterLine1();
                messageFooter2 = notificationMessage.getFooterLine2();
                messageFooter3 = notificationMessage.getFooterLine3();
                url = notificationMessage.getUrl();
                subject = notificationMessage.getSubject();
                break;
            }
        }

        StringBuilder messageBuilder = new StringBuilder();

        try {

            // Reading the download url from the cdm-config.xml file
            EmailConfigurations emailConfig =
                    DeviceConfigurationManager.getInstance().getDeviceManagementConfig().
                            getDeviceManagementConfigRepository().getEmailConfigurations();
            emailMessageProperties.setEnrolmentUrl(emailConfig.getlBHostPortPrefix()+ emailConfig.getEnrollmentContextPath());


            messageHeader = messageHeader.replaceAll("\\{" + EmailConstants.EnrolmentEmailConstants.FIRST_NAME + "\\}",
                    URLEncoder.encode(emailMessageProperties.getFirstName(),
                            EmailConstants.EnrolmentEmailConstants.ENCODED_SCHEME));

            messageBody = messageBody.trim().replaceAll("\\{" + EmailConstants.EnrolmentEmailConstants
                            .USERNAME
                            + "\\}",
                    URLEncoder.encode(emailMessageProperties.getUserName(), EmailConstants.EnrolmentEmailConstants
                            .ENCODED_SCHEME));

            messageBody = messageBody.replaceAll("\\{" + EmailConstants.EnrolmentEmailConstants.PASSWORD + "\\}",
                    URLEncoder.encode(emailMessageProperties.getPassword(), EmailConstants.EnrolmentEmailConstants
                            .ENCODED_SCHEME));

            messageBody = messageBody + System.getProperty("line.separator") + url.replaceAll("\\{"
                            + EmailConstants.EnrolmentEmailConstants.DOWNLOAD_URL + "\\}",
                    URLDecoder.decode(emailMessageProperties.getEnrolmentUrl(),
                            EmailConstants.EnrolmentEmailConstants.ENCODED_SCHEME));

            messageBuilder.append(messageHeader).append(System.getProperty("line.separator"));
            messageBuilder.append(messageBody).append(System.getProperty("line.separator")).append(
                    messageFooter1.trim());
            messageBuilder.append(System.getProperty("line.separator")).append(messageFooter2.trim());
            messageBuilder.append(System.getProperty("line.separator")).append(messageFooter3.trim());

        } catch (IOException e) {
            throw new DeviceManagementException("Error replacing tags in email template '" +
                    emailMessageProperties.getSubject() + "'", e);
        }
        emailMessageProperties.setMessageBody(messageBuilder.toString());
        emailMessageProperties.setSubject(subject);
        EmailServiceDataHolder.getInstance().getEmailServiceProvider().sendEmail(emailMessageProperties);
    }

    @Override
    public Device getDevice(DeviceIdentifier deviceId) throws DeviceManagementException {
        Device device;
        try {
            DeviceManagementDAOFactory.openConnection();
            device = deviceDAO.getDevice(deviceId, this.getTenantId());
        } catch (DeviceManagementDAOException e) {
            throw new DeviceManagementException("Error occurred while obtaining the device for id " +
                    "'" + deviceId.getId() + "'", e);
        } catch (SQLException e) {
            throw new DeviceManagementException("Error occurred while opening a connection to the data source", e);
        } finally {
            DeviceManagementDAOFactory.closeConnection();
        }
        if (device != null) {
            // The changes made here to prevent unit tests getting failed. They failed because when running the unit
            // tests there is no osgi services. So getDeviceManager() returns a null.
            DeviceManager deviceManager = this.getDeviceManager(deviceId.getType());
            if (deviceManager == null) {
                if (log.isDebugEnabled()) {
                    log.debug("Device Manager associated with the device type '" + deviceId.getType() + "' is null. " +
                            "Therefore, not attempting method 'getDevice'");
                }
                return device;
            }
            Device pluginSpecificInfo = deviceManager.getDevice(deviceId);
            if (pluginSpecificInfo != null) {
                device.setFeatures(pluginSpecificInfo.getFeatures());
                device.setProperties(pluginSpecificInfo.getProperties());
            }
        }
        return device;
    }

    @Override
    public Device getDevice(DeviceIdentifier deviceId, EnrolmentInfo.Status status) throws DeviceManagementException {
        Device device;
        try {
            DeviceManagementDAOFactory.openConnection();
            device = deviceDAO.getDevice(deviceId, status, this.getTenantId());
        } catch (DeviceManagementDAOException e) {
            throw new DeviceManagementException("Error occurred while obtaining the device for id " +
                                                "'" + deviceId.getId() + "'", e);
        } catch (SQLException e) {
            throw new DeviceManagementException("Error occurred while opening a connection to the data source", e);
        } finally {
            DeviceManagementDAOFactory.closeConnection();
        }
        if (device != null) {
            // The changes made here to prevent unit tests getting failed. They failed because when running the unit
            // tests there is no osgi services. So getDeviceManager() returns a null.
            DeviceManager deviceManager = this.getDeviceManager(deviceId.getType());
            if (deviceManager == null) {
                if (log.isDebugEnabled()) {
                    log.debug("Device Manager associated with the device type '" + deviceId.getType() + "' is null. " +
                              "Therefore, not attempting method 'getDevice'");
                }
                return device;
            }
            Device pluginSpecificInfo = deviceManager.getDevice(deviceId);
            if (pluginSpecificInfo != null) {
                device.setFeatures(pluginSpecificInfo.getFeatures());
                device.setProperties(pluginSpecificInfo.getProperties());
            }
        }
        return device;
    }

    @Override
    public List<DeviceType> getAvailableDeviceTypes() throws DeviceManagementException {
        List<DeviceType> deviceTypesInDatabase;
        List<DeviceType> deviceTypesResponse = new ArrayList<>();
        try {
            DeviceManagementDAOFactory.openConnection();
            deviceTypesInDatabase = deviceDAO.getDeviceTypes();
            Map<String, DeviceManagementService> registeredTypes = pluginRepository.getAllDeviceManagementServices();
            DeviceType deviceType;

            if (registeredTypes != null && deviceTypesInDatabase != null) {
                for (int x = 0; x < deviceTypesInDatabase.size(); x++) {
                    if (registeredTypes.get(deviceTypesInDatabase.get(x).getName()) != null) {
                        deviceType = new DeviceType();
                        deviceType.setId(deviceTypesInDatabase.get(x).getId());
                        deviceType.setName(deviceTypesInDatabase.get(x).getName());
                        deviceTypesResponse.add(deviceType);
                    }
                }
            }
        } catch (DeviceManagementDAOException e) {
            throw new DeviceManagementException("Error occurred while obtaining the device types.", e);
        } catch (SQLException e) {
            throw new DeviceManagementException("Error occurred while opening a connection to the data source", e);
        } finally {
            DeviceManagementDAOFactory.closeConnection();
        }
        return deviceTypesResponse;
    }

	@Override
    public boolean updateDeviceInfo(DeviceIdentifier deviceId, Device device) throws DeviceManagementException {
        DeviceManager deviceManager = this.getDeviceManager(deviceId.getType());
        if (deviceManager == null) {
            if (log.isDebugEnabled()) {
                log.debug("Device Manager associated with the device type '" + deviceId.getType() + "' is null. " +
                        "Therefore, not attempting method 'updateDeviceInfo'");
            }
            return false;
        }
        return deviceManager.updateDeviceInfo(deviceId, device);
    }

    @Override
    public boolean setOwnership(DeviceIdentifier deviceId, String ownershipType) throws DeviceManagementException {
        DeviceManager deviceManager = this.getDeviceManager(deviceId.getType());
        if (deviceManager == null) {
            if (log.isDebugEnabled()) {
                log.debug("Device Manager associated with the device type '" + deviceId.getType() + "' is null. " +
                        "Therefore, not attempting method 'setOwnership'");
            }
            return false;
        }
        return deviceManager.setOwnership(deviceId, ownershipType);
    }

    @Override
    public boolean isClaimable(DeviceIdentifier deviceId) throws DeviceManagementException {
        DeviceManager deviceManager = this.getDeviceManager(deviceId.getType());
        if (deviceManager == null) {
            if (log.isDebugEnabled()) {
                log.debug("Device Manager associated with the device type '" + deviceId.getType() + "' is null. " +
                        "Therefore, not attempting method 'isClaimable'");
            }
            return false;
        }
        return deviceManager.isClaimable(deviceId);
    }

    @Override
    public boolean setStatus(DeviceIdentifier deviceId, String currentOwner,
                             EnrolmentInfo.Status status) throws DeviceManagementException {
        try {
            DeviceManagementDAOFactory.beginTransaction();

            int tenantId = this.getTenantId();
            Device device = deviceDAO.getDevice(deviceId, tenantId);
            boolean success = enrollmentDAO.setStatus(device.getId(), currentOwner, status, tenantId);

            DeviceManagementDAOFactory.commitTransaction();
            return success;
        } catch (DeviceManagementDAOException e) {
            DeviceManagementDAOFactory.rollbackTransaction();
            throw new DeviceManagementException("Error occurred while setting enrollment status", e);
        } catch (TransactionManagementException e) {
            throw new DeviceManagementException("Error occurred while initiating transaction", e);
        } finally {
            DeviceManagementDAOFactory.closeConnection();
        }
    }

    @Override
    public License getLicense(String deviceType, String languageCode) throws DeviceManagementException {
        DeviceManager deviceManager = this.getDeviceManager(deviceType);
        License license;
        if (deviceManager == null) {
            if (log.isDebugEnabled()) {
                log.debug("Device Manager associated with the device type '" + deviceType + "' is null. " +
                        "Therefore, not attempting method 'getLicense'");
            }
            return null;
        }
        try {
            license = deviceManager.getLicense(languageCode);
            if (license == null) {
                if (log.isDebugEnabled()) {
                    log.debug("Cannot find a license for '" + deviceType + "' device type");
                }
            }
            return license;
        } catch (LicenseManagementException e) {
            throw new DeviceManagementException("Error occurred while retrieving license configured for " +
                    "device type '" + deviceType + "' and language code '" + languageCode + "'", e);
        }
    }

    @Override
    public void addLicense(String deviceType, License license) throws DeviceManagementException {
        DeviceManager deviceManager = this.getDeviceManager(deviceType);
        if (deviceManager == null) {
            if (log.isDebugEnabled()) {
                log.debug("Device Manager associated with the device type '" + deviceType + "' is null. " +
                        "Therefore, not attempting method 'isEnrolled'");
            }
            return;
        }
        try {
            deviceManager.addLicense(license);
        } catch (LicenseManagementException e) {
            throw new DeviceManagementException("Error occurred while adding license for " +
                    "device type '" + deviceType + "'", e);
        }
    }

    private DeviceManagementPluginRepository getPluginRepository() {
        return pluginRepository;
    }

    @Override
    public int addOperation(Operation operation,
                            List<DeviceIdentifier> devices) throws OperationManagementException {
        return DeviceManagementDataHolder.getInstance().getOperationManager().addOperation(operation, devices);
    }

    @Override
    public List<? extends Operation> getOperations(DeviceIdentifier deviceId) throws OperationManagementException {
        return DeviceManagementDataHolder.getInstance().getOperationManager().getOperations(deviceId);
    }

    @Override
    public List<? extends Operation> getPendingOperations(DeviceIdentifier deviceId)
            throws OperationManagementException {
        return DeviceManagementDataHolder.getInstance().getOperationManager().getPendingOperations(deviceId);
    }

    @Override
    public Operation getNextPendingOperation(DeviceIdentifier deviceId) throws OperationManagementException {
        return DeviceManagementDataHolder.getInstance().getOperationManager().getNextPendingOperation(deviceId);
    }

    @Override
    public void updateOperation(DeviceIdentifier deviceId, Operation operation) throws OperationManagementException {
        DeviceManagementDataHolder.getInstance().getOperationManager().updateOperation(deviceId, operation);
    }

    @Override
    public void deleteOperation(int operationId) throws OperationManagementException {
        DeviceManagementDataHolder.getInstance().getOperationManager().deleteOperation(operationId);
    }

    @Override
    public Operation getOperationByDeviceAndOperationId(DeviceIdentifier deviceId,
                                                        int operationId) throws OperationManagementException {
        return DeviceManagementDataHolder.getInstance().getOperationManager().getOperationByDeviceAndOperationId(
                deviceId, operationId);
    }

    @Override
    public List<? extends Operation> getOperationsByDeviceAndStatus(
            DeviceIdentifier deviceId,
            Operation.Status status) throws OperationManagementException, DeviceManagementException {
        return DeviceManagementDataHolder.getInstance().getOperationManager().getOperationsByDeviceAndStatus(
                deviceId, status);
    }

    @Override
    public Operation getOperation(int operationId) throws OperationManagementException {
        return DeviceManagementDataHolder.getInstance().getOperationManager().getOperation(operationId);
    }

    @Override
    public List<Device> getDevicesOfUser(String username) throws DeviceManagementException {
        List<Device> devices = new ArrayList<>();
        List<Device> userDevices;
        try {
            DeviceManagementDAOFactory.openConnection();
            userDevices = deviceDAO.getDevicesOfUser(username, this.getTenantId());
        } catch (DeviceManagementDAOException e) {
            throw new DeviceManagementException("Error occurred while retrieving the list of devices that " +
                    "belong to the user '" + username + "'", e);
        } catch (SQLException e) {
            throw new DeviceManagementException("Error occurred while opening a connection to the data source", e);
        } finally {
            DeviceManagementDAOFactory.closeConnection();
        }

        for (Device device : userDevices) {
            DeviceManager deviceManager = this.getDeviceManager(device.getType());
            if (deviceManager == null) {
                if (log.isDebugEnabled()) {
                    log.debug("Device Manager associated with the device type '" + device.getType() + "' is null. " +
                            "Therefore, not attempting method 'isEnrolled'");
                }
                devices.add(device);
                continue;
            }
            Device dmsDevice =
                    deviceManager.getDevice(new DeviceIdentifier(device.getDeviceIdentifier(), device.getType()));
            if (dmsDevice != null) {
                device.setFeatures(dmsDevice.getFeatures());
                device.setProperties(dmsDevice.getProperties());
            }
            devices.add(device);
        }
        return devices;

    }

    @Override
    public List<Device> getAllDevicesOfRole(String role) throws DeviceManagementException {
        List<Device> devices = new ArrayList<>();
        String[] users;
        int tenantId = this.getTenantId();
        try {
            users = DeviceManagementDataHolder.getInstance().getRealmService().getTenantUserRealm(tenantId)
                    .getUserStoreManager().getUserListOfRole(role);
        } catch (UserStoreException e) {
            throw new DeviceManagementException("Error occurred while obtaining the users, who are assigned " +
                    "with the role '" + role + "'", e);
        }

        List<Device> userDevices;
        for (String user : users) {
            userDevices = new ArrayList<>();
            try {
                DeviceManagementDAOFactory.openConnection();
                userDevices = deviceDAO.getDevicesOfUser(user, tenantId);
            } catch (DeviceManagementDAOException | SQLException e) {
                log.error("Error occurred while obtaining the devices of user '" + user + "'", e);
            } finally {
                DeviceManagementDAOFactory.closeConnection();
            }
            for (Device device : userDevices) {
                Device dmsDevice = this.getDeviceManager(device.getType()).
                        getDevice(new DeviceIdentifier(device.getDeviceIdentifier(), device.getType()));
                if (dmsDevice != null) {
                    device.setFeatures(dmsDevice.getFeatures());
                    device.setProperties(dmsDevice.getProperties());
                }
                devices.add(device);
            }
        }
        return devices;
    }

    @Override
    public int getDeviceCount() throws DeviceManagementException {
        try {
            DeviceManagementDAOFactory.openConnection();
            return deviceDAO.getDeviceCount(this.getTenantId());
        } catch (DeviceManagementDAOException e) {
            throw new DeviceManagementException("Error occurred while retrieving the device count", e);
        } catch (SQLException e) {
            throw new DeviceManagementException("Error occurred while opening a connection to the data source", e);
        } finally {
            DeviceManagementDAOFactory.closeConnection();
        }
    }

    @Override
    public List<Device> getDevicesByName(String deviceName) throws DeviceManagementException {
        List<Device> devices = new ArrayList<>();
        List<Device> allDevices;
        try {
            DeviceManagementDAOFactory.openConnection();
            allDevices = deviceDAO.getDevicesByName(deviceName, this.getTenantId());
        } catch (DeviceManagementDAOException e) {
            throw new DeviceManagementException("Error occurred while fetching the list of devices that matches to '"
                    + deviceName + "'", e);
        } catch (SQLException e) {
            throw new DeviceManagementException("Error occurred while opening a connection to the data source", e);
        } finally {
            DeviceManagementDAOFactory.closeConnection();
        }
        for (Device device : allDevices) {
            Device dmsDevice = this.getDeviceManager(device.getType()).
                    getDevice(new DeviceIdentifier(device.getDeviceIdentifier(), device.getType()));
            if (dmsDevice != null) {
                device.setFeatures(dmsDevice.getFeatures());
                device.setProperties(dmsDevice.getProperties());
            }
            devices.add(device);
        }
        return devices;

    }

    @Override
    public void updateDeviceEnrolmentInfo(Device device, EnrolmentInfo.Status status) throws DeviceManagementException {
        try {
            DeviceManagementDAOFactory.beginTransaction();

            DeviceType deviceType = deviceTypeDAO.getDeviceType(device.getType());
            device.getEnrolmentInfo().setDateOfLastUpdate(new Date().getTime());
            device.getEnrolmentInfo().setStatus(status);
            deviceDAO.updateDevice(deviceType.getId(), device, this.getTenantId());

            DeviceManagementDAOFactory.commitTransaction();
        } catch (DeviceManagementDAOException e) {
            DeviceManagementDAOFactory.rollbackTransaction();
            throw new DeviceManagementException("Error occurred update device enrolment status : '" +
                    device.getId() + "'", e);
        } catch (TransactionManagementException e) {
            throw new DeviceManagementException("Error occurred while initiating transaction", e);
        } finally {
            DeviceManagementDAOFactory.closeConnection();
        }
    }

    @Override
    public void registerDeviceManagementService(DeviceManagementService deviceManagementService) {
        try {
            pluginRepository.addDeviceManagementProvider(deviceManagementService);
        } catch (DeviceManagementException e) {
            log.error("Error occurred while registering device management plugin '" +
                    deviceManagementService.getType() + "'", e);
        }
    }

    @Override
    public void unregisterDeviceManagementService(DeviceManagementService deviceManagementService) {
        try {
            pluginRepository.removeDeviceManagementProvider(deviceManagementService);
        } catch (DeviceManagementException e) {
            log.error("Error occurred while un-registering device management plugin '" +
                    deviceManagementService.getType() + "'", e);
        }
    }

    public List<Device> getDevicesByStatus(EnrolmentInfo.Status status) throws DeviceManagementException {
        List<Device> devices = new ArrayList<>();
        List<Device> allDevices;
        try {
            DeviceManagementDAOFactory.openConnection();
            allDevices = deviceDAO.getDevicesByStatus(status, this.getTenantId());
        } catch (DeviceManagementDAOException e) {
            throw new DeviceManagementException(
                    "Error occurred while fetching the list of devices that matches to status: '" + status + "'", e);
        } catch (SQLException e) {
            throw new DeviceManagementException("Error occurred while opening a connection to the data source", e);
        } finally {
            DeviceManagementDAOFactory.closeConnection();
        }

        for (Device device : allDevices) {
            Device dmsDevice = this.getDeviceManager(device.getType()).
                    getDevice(new DeviceIdentifier(device.getDeviceIdentifier(), device.getType()));
            if (dmsDevice != null) {
                device.setFeatures(dmsDevice.getFeatures());
                device.setProperties(dmsDevice.getProperties());
            }
            devices.add(device);
        }
        return devices;
    }

    private int getTenantId() {
        return CarbonContext.getThreadLocalCarbonContext().getTenantId();
    }

    private DeviceManager getDeviceManager(String deviceType) {
        DeviceManagementService deviceManagementService =
                this.getPluginRepository().getDeviceManagementService(deviceType);
        if (deviceManagementService == null) {
            if (log.isDebugEnabled()) {
                log.debug("Device type '" + deviceType + "' does not have an associated device management " +
                        "plugin registered within the framework. Therefore, returning null");
            }
            return null;
        }
        return deviceManagementService.getDeviceManager();
    }

}
