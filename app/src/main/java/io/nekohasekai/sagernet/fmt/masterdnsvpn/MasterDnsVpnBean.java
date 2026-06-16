/******************************************************************************
 * Copyright (C) 2026 by nekohasekai <contact-git@sekai.icu>                  *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.masterdnsvpn;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

/**
 * MasterDnsVPN (github.com/masterking32/MasterDnsVPN) DNS-tunneling client, run as a
 * bundled native-binary sidecar. The carrier is DNS queries to the configured resolvers;
 * on Android the upstream sockets are protected from the VPN via the forked client's
 * FD_CONTROL_UNIX_SOCKET hook pointed at libcore's protect_path server.
 */
public class MasterDnsVpnBean extends AbstractBean {

    // --- Tunnel identity & security ---
    public String domains;               // newline/comma separated tunnel domains
    public Integer dataEncryptionMethod; // 0 None,1 XOR,2 ChaCha20,3 AES-128,4 AES-192,5 AES-256
    public String encryptionKey;
    public String resolvers;             // one per line (e.g. 8.8.8.8, 1.1.1.1:5353)

    // --- Resolver selection / resilience ---
    public Integer resolverBalancingStrategy; // 1..8
    public Integer packetDuplicationCount;     // 1..10
    public Integer setupPacketDuplicationCount;
    public Boolean autoDisableTimeoutServers;
    public Boolean autoRemoveLowMtuServers;
    public Boolean baseEncodeData;

    // --- Compression ---
    public Integer uploadCompressionType;   // 0 OFF,1 ZSTD,2 LZ4,3 ZLIB
    public Integer downloadCompressionType;
    public Integer compressionMinSize;

    // --- MTU discovery ---
    public Integer minUploadMtu;
    public Integer minDownloadMtu;
    public Integer maxUploadMtu;
    public Integer maxDownloadMtu;

    // --- Local DNS service ---
    public Boolean localDnsEnabled;
    public Integer localDnsPort;

    // --- Logging ---
    public String logLevel; // e.g. "INFO", "DEBUG"

    // --- Escape hatch: raw JSON merged over the generated config ---
    public String advancedJson;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (domains == null) domains = "";
        // Default to None: encryption must match the server and needs a key, so don't
        // imply security with XOR + an empty key. The user picks a cipher + key explicitly.
        if (dataEncryptionMethod == null) dataEncryptionMethod = 0;
        if (encryptionKey == null) encryptionKey = "";
        if (resolvers == null) resolvers = "8.8.8.8\n1.1.1.1";
        if (resolverBalancingStrategy == null) resolverBalancingStrategy = 3;
        if (packetDuplicationCount == null) packetDuplicationCount = 3;
        if (setupPacketDuplicationCount == null) setupPacketDuplicationCount = 4;
        if (autoDisableTimeoutServers == null) autoDisableTimeoutServers = true;
        if (autoRemoveLowMtuServers == null) autoRemoveLowMtuServers = true;
        if (baseEncodeData == null) baseEncodeData = false;
        if (uploadCompressionType == null) uploadCompressionType = 0;
        if (downloadCompressionType == null) downloadCompressionType = 0;
        if (compressionMinSize == null) compressionMinSize = 120;
        if (minUploadMtu == null) minUploadMtu = 38;
        if (minDownloadMtu == null) minDownloadMtu = 200;
        if (maxUploadMtu == null) maxUploadMtu = 150;
        if (maxDownloadMtu == null) maxDownloadMtu = 4000;
        if (localDnsEnabled == null) localDnsEnabled = false;
        if (localDnsPort == null) localDnsPort = 53;
        if (logLevel == null) logLevel = "INFO";
        if (advancedJson == null) advancedJson = "";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(0);
        super.serialize(output);
        output.writeString(domains);
        output.writeInt(dataEncryptionMethod);
        output.writeString(encryptionKey);
        output.writeString(resolvers);
        output.writeInt(resolverBalancingStrategy);
        output.writeInt(packetDuplicationCount);
        output.writeInt(setupPacketDuplicationCount);
        output.writeBoolean(autoDisableTimeoutServers);
        output.writeBoolean(autoRemoveLowMtuServers);
        output.writeBoolean(baseEncodeData);
        output.writeInt(uploadCompressionType);
        output.writeInt(downloadCompressionType);
        output.writeInt(compressionMinSize);
        output.writeInt(minUploadMtu);
        output.writeInt(minDownloadMtu);
        output.writeInt(maxUploadMtu);
        output.writeInt(maxDownloadMtu);
        output.writeBoolean(localDnsEnabled);
        output.writeInt(localDnsPort);
        output.writeString(logLevel);
        output.writeString(advancedJson);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        domains = input.readString();
        dataEncryptionMethod = input.readInt();
        encryptionKey = input.readString();
        resolvers = input.readString();
        resolverBalancingStrategy = input.readInt();
        packetDuplicationCount = input.readInt();
        setupPacketDuplicationCount = input.readInt();
        autoDisableTimeoutServers = input.readBoolean();
        autoRemoveLowMtuServers = input.readBoolean();
        baseEncodeData = input.readBoolean();
        uploadCompressionType = input.readInt();
        downloadCompressionType = input.readInt();
        compressionMinSize = input.readInt();
        minUploadMtu = input.readInt();
        minDownloadMtu = input.readInt();
        maxUploadMtu = input.readInt();
        maxDownloadMtu = input.readInt();
        localDnsEnabled = input.readBoolean();
        localDnsPort = input.readInt();
        logLevel = input.readString();
        advancedJson = input.readString();
    }

    @Override
    public boolean canTCPing() {
        return false;
    }

    @Override
    public boolean canMapping() {
        // The sidecar connects directly to the configured DNS resolvers with sockets
        // protected via FD_CONTROL_UNIX_SOCKET, so no dokodemo-door mapping is needed.
        return false;
    }

    @NotNull
    @Override
    public MasterDnsVpnBean clone() {
        return KryoConverters.deserialize(new MasterDnsVpnBean(), KryoConverters.serialize(this));
    }

    public static final Creator<MasterDnsVpnBean> CREATOR = new CREATOR<MasterDnsVpnBean>() {
        @NonNull
        @Override
        public MasterDnsVpnBean newInstance() {
            return new MasterDnsVpnBean();
        }

        @Override
        public MasterDnsVpnBean[] newArray(int size) {
            return new MasterDnsVpnBean[size];
        }
    };
}
