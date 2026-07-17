package io.nekohasekai.sagernet.fmt.snell;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class SnellBean extends AbstractBean {

    public String psk;
    public String userKey;
    public Integer version;      // 1-6
    public String obfsMode;      // "", "http", "tls"
    public String obfsHost;
    public String mode;          // v6: "", "default", "unshaped", "unsafe-raw"
    public Boolean reuse;
    public String network;       // "tcp", "udp", "tcp,udp"

    @Override
    public void initializeDefaultValues() {
        if (serverPort == null) serverPort = 443;
        if (version == null) version = 4;
        if (psk == null) psk = "";
        if (userKey == null) userKey = "";
        if (obfsMode == null) obfsMode = "";
        if (obfsHost == null) obfsHost = "";
        if (mode == null || mode.isEmpty()) mode = "default";
        if (reuse == null) reuse = false;
        if (network == null) network = "";

        super.initializeDefaultValues();
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(3); // version
        super.serialize(output);
        output.writeString(psk);
        output.writeInt(version);
        output.writeString(obfsMode);
        output.writeString(obfsHost);
        output.writeBoolean(reuse);
        output.writeString(network);
        output.writeString(userKey);
        output.writeString(mode);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        psk = input.readString();
        this.version = input.readInt();
        obfsMode = input.readString();
        obfsHost = input.readString();
        reuse = input.readBoolean();
        if (version >= 2) {
            network = input.readString();
        }
        if (version >= 3) {
            userKey = input.readString();
            mode = input.readString();
        }
    }

    @NotNull
    @Override
    public SnellBean clone() {
        return KryoConverters.deserialize(new SnellBean(), KryoConverters.serialize(this));
    }

    public static final Creator<SnellBean> CREATOR = new CREATOR<SnellBean>() {
        @NonNull
        @Override
        public SnellBean newInstance() {
            return new SnellBean();
        }

        @Override
        public SnellBean[] newArray(int size) {
            return new SnellBean[size];
        }
    };

    @NotNull
    @Override
    public String displayName() {
        return name;
    }

    @NotNull
    @Override
    public String displayAddress() {
        return serverAddress;
    }
}
