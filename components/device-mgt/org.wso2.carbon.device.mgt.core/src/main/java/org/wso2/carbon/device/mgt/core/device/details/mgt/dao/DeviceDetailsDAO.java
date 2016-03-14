/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */


package org.wso2.carbon.device.mgt.core.device.details.mgt.dao;

import org.wso2.carbon.device.mgt.common.device.details.DeviceApplication;
import org.wso2.carbon.device.mgt.common.device.details.DeviceInfo;
import org.wso2.carbon.device.mgt.common.device.details.DeviceLocation;
import org.wso2.carbon.device.mgt.core.device.details.mgt.DeviceDetailsMgtDAOException;

/**
 * This class will store device details related generic information such as cpu/memory utilization, battery level,
 * plugged in to a power source or operation on battery.
 */

public interface DeviceDetailsDAO {

    /**
     * This method will add device information to the database.
     * @param deviceInfo - Device information object.
     * @throws DeviceDetailsMgtDAOException
     */
    void addDeviceInformation(DeviceInfo deviceInfo) throws DeviceDetailsMgtDAOException;

    /**
     * This method will return the device information when device id is provided.
     * @param deviceId - device Id
     * @return DeviceInfo
     * @throws DeviceDetailsMgtDAOException
     */
    DeviceInfo getDeviceInformation(int deviceId) throws DeviceDetailsMgtDAOException;

    /**
     * This method will add device location to database.
     * @param deviceLocation  - Device location with latitude and longitude.
     * @throws DeviceDetailsMgtDAOException
     */
    void addDeviceLocation(DeviceLocation deviceLocation) throws DeviceDetailsMgtDAOException;

    /**
     * This method will return the device location object when the device id is provided.
     * @param deviceId - id of the device.
     * @return - Device location object.
     * @throws DeviceDetailsMgtDAOException
     */
    DeviceLocation getDeviceLocation(int deviceId) throws DeviceDetailsMgtDAOException;

    /**
     * This method will add device application to database.
     * @param deviceApplication - Device application
     * @throws DeviceDetailsMgtDAOException
     */
    void addDeviceApplications(DeviceApplication deviceApplication) throws DeviceDetailsMgtDAOException;

    /**
     * This method will return the device application list once device id is provided.
     * @param deviceId
     * @return
     * @throws DeviceDetailsMgtDAOException
     */
    DeviceApplication getDeviceApplications(int deviceId) throws DeviceDetailsMgtDAOException;
}