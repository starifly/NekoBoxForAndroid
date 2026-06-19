package io.nekohasekai.sagernet.fmt.hysteria;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;
import io.nekohasekai.sagernet.ktx.NetsKt;
import kotlin.text.StringsKt;

public class HysteriaBean extends AbstractBean {
    public Integer protocolVersion;

    // Use serverPorts instead of serverPort
    public String serverPorts;

    // HY1 & 2

    public String authPayload;
    public String obfuscation;
    public String sni;
    public String caText;
    public Integer uploadMbps;
    public Integer downloadMbps;
    public Boolean allowInsecure;
    public Integer streamReceiveWindow;
    public Integer connectionReceiveWindow;
    public Boolean disableMtuDiscovery;
    public Integer hopInterval;

    // HY2 advanced QUIC options. Zero means unset/use Hysteria defaults.
    public Integer hy2InitialStreamReceiveWindow;
    public Integer hy2MaxStreamReceiveWindow;
    public Integer hy2InitialConnectionReceiveWindow;
    public Integer hy2MaxConnectionReceiveWindow;
    public Integer hy2MaxIdleTimeout;
    public Integer hy2KeepAlivePeriod;
    public Integer hy2MinHopInterval;
    public Integer hy2MaxHopInterval;

    // HY1

    public String alpn;

    public static final int TYPE_NONE = 0;
    public static final int TYPE_STRING = 1;
    public static final int TYPE_BASE64 = 2;
    public Integer authPayloadType;

    public static final int PROTOCOL_UDP = 0;
    public static final int PROTOCOL_FAKETCP = 1;
    public static final int PROTOCOL_WECHAT_VIDEO = 2;
    public Integer protocol;

    // Hysteria2 obfuscation type (HY2 only). The shared `obfuscation` field holds the password.
    public static final int OBFS_NONE = 0;
    public static final int OBFS_SALAMANDER = 1;
    public static final int OBFS_GECKO = 2;
    public Integer hysteria2ObfsType;
    // Gecko obfs packet-size bounds (HY2 + Gecko only).
    public Integer geckoMinPacketSize;
    public Integer geckoMaxPacketSize;

    @Override
    public boolean canMapping() {
        return protocol != PROTOCOL_FAKETCP;
    }

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (protocolVersion == null) protocolVersion = 2;

        if (authPayloadType == null) authPayloadType = TYPE_NONE;
        if (authPayload == null) authPayload = "";
        if (protocol == null) protocol = PROTOCOL_UDP;
        if (obfuscation == null) obfuscation = "";
        if (hysteria2ObfsType == null) {
            // Backward compat: pre-Gecko HY2 profiles with a password used Salamander.
            hysteria2ObfsType = obfuscation.isEmpty() ? OBFS_NONE : OBFS_SALAMANDER;
        }
        if (geckoMinPacketSize == null) geckoMinPacketSize = 512;
        if (geckoMaxPacketSize == null) geckoMaxPacketSize = 1200;
        if (sni == null) sni = "";
        if (alpn == null) alpn = "";
        if (caText == null) caText = "";
        if (allowInsecure == null) allowInsecure = false;

        if (protocolVersion == 1) {
            if (uploadMbps == null) uploadMbps = 10;
            if (downloadMbps == null) downloadMbps = 50;
        } else {
            if (uploadMbps == null) uploadMbps = 0;
            if (downloadMbps == null) downloadMbps = 0;
        }

        if (streamReceiveWindow == null) streamReceiveWindow = 0;
        if (connectionReceiveWindow == null) connectionReceiveWindow = 0;
        if (disableMtuDiscovery == null) disableMtuDiscovery = false;
        if (hopInterval == null) hopInterval = 10;
        if (hy2InitialStreamReceiveWindow == null) hy2InitialStreamReceiveWindow = 0;
        if (hy2MaxStreamReceiveWindow == null) hy2MaxStreamReceiveWindow = 0;
        if (hy2InitialConnectionReceiveWindow == null) hy2InitialConnectionReceiveWindow = 0;
        if (hy2MaxConnectionReceiveWindow == null) hy2MaxConnectionReceiveWindow = 0;
        if (hy2MaxIdleTimeout == null) hy2MaxIdleTimeout = 0;
        if (hy2KeepAlivePeriod == null) hy2KeepAlivePeriod = 0;
        if (hy2MinHopInterval == null) hy2MinHopInterval = 0;
        if (hy2MaxHopInterval == null) hy2MaxHopInterval = 0;
        if (serverPorts == null) serverPorts = "443";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(9);
        super.serialize(output);

        output.writeInt(protocolVersion);

        output.writeInt(authPayloadType);
        output.writeString(authPayload);
        output.writeInt(protocol);
        output.writeString(obfuscation);
        output.writeString(sni);
        output.writeString(alpn);

        output.writeInt(uploadMbps);
        output.writeInt(downloadMbps);
        output.writeBoolean(allowInsecure);

        output.writeString(caText);
        output.writeInt(streamReceiveWindow);
        output.writeInt(connectionReceiveWindow);
        output.writeBoolean(disableMtuDiscovery);
        output.writeInt(hopInterval);
        output.writeString(serverPorts);

        output.writeInt(hysteria2ObfsType);
        output.writeInt(geckoMinPacketSize);
        output.writeInt(geckoMaxPacketSize);

        output.writeInt(hy2InitialStreamReceiveWindow);
        output.writeInt(hy2MaxStreamReceiveWindow);
        output.writeInt(hy2InitialConnectionReceiveWindow);
        output.writeInt(hy2MaxConnectionReceiveWindow);
        output.writeInt(hy2MaxIdleTimeout);
        output.writeInt(hy2KeepAlivePeriod);
        output.writeInt(hy2MinHopInterval);
        output.writeInt(hy2MaxHopInterval);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        if (version >= 7) {
            protocolVersion = input.readInt();
        } else {
            protocolVersion = 1;
        }
        authPayloadType = input.readInt();
        authPayload = input.readString();
        if (version >= 3) {
            protocol = input.readInt();
        }
        obfuscation = input.readString();
        sni = input.readString();
        if (version >= 2) {
            alpn = input.readString();
        }
        uploadMbps = input.readInt();
        downloadMbps = input.readInt();
        allowInsecure = input.readBoolean();
        if (version >= 1) {
            caText = input.readString();
            streamReceiveWindow = input.readInt();
            connectionReceiveWindow = input.readInt();
            if (version != 4) disableMtuDiscovery = input.readBoolean(); // note: skip 4
        }
        if (version >= 5) {
            hopInterval = input.readInt();
        }
        if (version >= 6) {
            serverPorts = input.readString();
        } else {
            // old update to new
            if (HysteriaFmtKt.isMultiPort(serverAddress)) {
                serverPorts = StringsKt.substringAfterLast(serverAddress, ":", serverAddress);
                serverAddress = StringsKt.substringBeforeLast(serverAddress, ":", serverAddress);
            } else {
                serverPorts = serverPort.toString();
            }
        }
        if (version >= 8) {
            hysteria2ObfsType = input.readInt();
            geckoMinPacketSize = input.readInt();
            geckoMaxPacketSize = input.readInt();
        }
        if (version >= 9) {
            hy2InitialStreamReceiveWindow = input.readInt();
            hy2MaxStreamReceiveWindow = input.readInt();
            hy2InitialConnectionReceiveWindow = input.readInt();
            hy2MaxConnectionReceiveWindow = input.readInt();
            hy2MaxIdleTimeout = input.readInt();
            hy2KeepAlivePeriod = input.readInt();
            hy2MinHopInterval = input.readInt();
            hy2MaxHopInterval = input.readInt();
        }
        // For version < 8, hysteria2ObfsType/gecko* stay null and are derived in
        // initializeDefaultValues() (Salamander when an obfuscation password exists).
    }

    @Override
    public String displayAddress() {
        return NetsKt.wrapIPV6Host(serverAddress) + ":" + serverPorts;
    }

    @Override
    public boolean canTCPing() {
        return false;
    }

    @NotNull
    @Override
    public HysteriaBean clone() {
        return KryoConverters.deserialize(new HysteriaBean(), KryoConverters.serialize(this));
    }

    public static final Creator<HysteriaBean> CREATOR = new CREATOR<HysteriaBean>() {
        @NonNull
        @Override
        public HysteriaBean newInstance() {
            return new HysteriaBean();
        }

        @Override
        public HysteriaBean[] newArray(int size) {
            return new HysteriaBean[size];
        }
    };
}
