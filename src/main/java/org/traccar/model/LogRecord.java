/*
 * Copyright 2023 - 2024 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.traccar.session.ConnectionKey;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Date;

public class LogRecord {

    private final InetSocketAddress localAddress;
    private final InetSocketAddress remoteAddress;

    public LogRecord(SocketAddress localAddress, SocketAddress remoteAddress) {
        this.localAddress = (InetSocketAddress) localAddress;
        this.remoteAddress = (InetSocketAddress) remoteAddress;
    }

    @JsonIgnore
    public InetSocketAddress getAddress() {
        return remoteAddress;
    }

    @JsonIgnore
    public ConnectionKey getConnectionKey() {
        return new ConnectionKey(localAddress, remoteAddress);
    }

    public String getHost() {
        return remoteAddress.getHostString();
    }

    // Fork: when the server read the message. Positions decoded from it get a serverTime right after this, which
    // lets clients match each log record to the position it produced
    private final Date time = new Date();

    public Date getTime() {
        return time;
    }

    private String protocol;

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    private String uniqueId;

    public String getUniqueId() {
        return uniqueId;
    }

    public void setUniqueId(String uniqueId) {
        this.uniqueId = uniqueId;
    }

    private long deviceId;

    public long getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(long deviceId) {
        this.deviceId = deviceId;
    }

    private String data;

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

}
