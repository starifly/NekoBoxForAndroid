package io.nekohasekai.sagernet.fmt.trojan_go;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;
import moe.matsuri.nb4a.utils.JavaUtil;

public class TrojanGoBean extends AbstractBean {

    /**
     * Trojan password.
     * Must not be omitted or empty; non-ASCII printable characters are discouraged.
     * Must be encoded with encodeURIComponent.
     */
    public String password;

    /**
     * Custom TLS SNI.
     * Defaults to the same value as trojan-host when omitted. Must not be empty.
     * <p>
     * Must be encoded with encodeURIComponent.
     */
    public String sni;

    /**
     * Transport type.
     * Defaults to "original" when omitted, but must not be an empty string.
     * Currently only "original" and "ws" are valid; future values may include h2, h2+ws, etc.
     * <p>
     * "original" uses the raw Trojan transport, which is not easily served via a CDN.
     * "ws" uses wss as the transport layer.
     */
    public String type;

    /**
     * Custom HTTP Host header.
     * May be omitted; defaults to trojan-host when omitted.
     * May be an empty string, but that can lead to unexpected behavior.
     * <p>
     * Warning: if your port is non-standard (not 80 / 443), the RFC requires the Host to
     * include the port after the hostname, e.g. example.com:44333. Whether to follow this
     * is up to you.
     * <p>
     * Must be encoded with encodeURIComponent.
     */
    public String host;

    /**
     * Effective when the transport type is ws, h2, or h2+ws.
     * Must not be omitted or empty.
     * Must start with "/".
     * May use URL characters such as & # ?, but must be a valid URL path.
     * <p>
     * Must be encoded with encodeURIComponent.
     */
    public String path;

    /**
     * Encryption layer used to cryptographically secure Trojan traffic.
     * May be omitted; defaults to "none" (no encryption).
     * Must not be an empty string.
     * <p>
     * Must be encoded with encodeURIComponent.
     * <p>
     * When using the Shadowsocks algorithm for traffic encryption, the format is:
     * <p>
     * ss;method:password
     * <p>
     * where "ss" is fixed and "method" is the encryption method, which must be one of:
     * <p>
     * aes-128-gcm
     * aes-256-gcm
     * chacha20-ietf-poly1305
     */
    public String encryption;

    /**
     * Extra plugin options. This field is reserved.
     * May be omitted, but must not be an empty string.
     */
    // not used in NB4A
    public String plugin;

    // ---

    public Boolean allowInsecure;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();

        if (password == null) password = "";
        if (sni == null) sni = "";
        if (JavaUtil.isNullOrBlank(type)) type = "original";
        if (host == null) host = "";
        if (path == null) path = "";
        if (JavaUtil.isNullOrBlank(encryption)) encryption = "none";
        if (plugin == null) plugin = "";
        if (allowInsecure == null) allowInsecure = false;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(1);
        super.serialize(output);
        output.writeString(password);
        output.writeString(sni);
        output.writeString(type);
        //noinspection SwitchStatementWithTooFewBranches
        switch (type) {
            case "ws": {
                output.writeString(host);
                output.writeString(path);
                break;
            }
        }
        output.writeString(encryption);
        output.writeString(plugin);
        output.writeBoolean(allowInsecure);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);

        password = input.readString();
        sni = input.readString();
        type = input.readString();
        //noinspection SwitchStatementWithTooFewBranches
        switch (type) {
            case "ws": {
                host = input.readString();
                path = input.readString();
                break;
            }
        }
        encryption = input.readString();
        plugin = input.readString();
        if (version >= 1) {
            allowInsecure = input.readBoolean();
        }
    }

    @NotNull
    @Override
    public TrojanGoBean clone() {
        return KryoConverters.deserialize(new TrojanGoBean(), KryoConverters.serialize(this));
    }

    public static final Creator<TrojanGoBean> CREATOR = new CREATOR<TrojanGoBean>() {
        @NonNull
        @Override
        public TrojanGoBean newInstance() {
            return new TrojanGoBean();
        }

        @Override
        public TrojanGoBean[] newArray(int size) {
            return new TrojanGoBean[size];
        }
    };
}
