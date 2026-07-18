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

package io.nekohasekai.sagernet.fmt.olcrtc;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

/**
 * olcRTC (github.com/openlibrecommunity/olcrtc) encrypted TCP-over-WebRTC tunnel client,
 * run as a child-process sidecar (libolcrtc.so) launched by the guarded process pool. The
 * carrier is a regular
 * call session on a common meet service (Jitsi, Telemost, WBStream); the client opens
 * a loopback SOCKS5 listener that a sing-box {@code socks} outbound dials. Upstream sockets
 * are protected from the tunnel via the library's Protector fd-hook wired to the platform
 * protect callback.
 */
public class OlcrtcBean extends AbstractBean {

    // --- Carrier & room ---
    public String carrier;   // jitsi | telemost | wbstream
    public String roomId;    // carrier-specific room id; for jitsi a full https://host/room

    // --- Identity & security ---
    // clientId == the CLIENT_HELLO device id. Against a stock server it can be any non-empty
    // string; if the operator installs a custom authorizer it acts as a pairing token.
    public String clientId;
    public String keyHex;    // 64-char hex (32 bytes) shared key

    // --- Transport ---
    // Only the two transports exposed by the library mobile API are offered.
    public String transport; // vp8channel | datachannel
    public Integer vp8Fps;
    public Integer vp8BatchSize;

    // --- Networking ---
    public String dnsServer; // resolver used by the tunnel, e.g. "9.9.9.9:53"

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (carrier == null) carrier = "jitsi";
        if (roomId == null) roomId = "";
        if (clientId == null) clientId = "";
        if (keyHex == null) keyHex = "";
        if (transport == null) transport = "vp8channel";
        if (vp8Fps == null) vp8Fps = 30;
        if (vp8BatchSize == null) vp8BatchSize = 8;
        if (dnsServer == null) dnsServer = "";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(0);
        super.serialize(output);
        output.writeString(carrier);
        output.writeString(roomId);
        output.writeString(clientId);
        output.writeString(keyHex);
        output.writeString(transport);
        output.writeInt(vp8Fps);
        output.writeInt(vp8BatchSize);
        output.writeString(dnsServer);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        carrier = input.readString();
        roomId = input.readString();
        clientId = input.readString();
        keyHex = input.readString();
        transport = input.readString();
        vp8Fps = input.readInt();
        vp8BatchSize = input.readInt();
        dnsServer = input.readString();
    }

    @Override
    public boolean canTCPing() {
        return false;
    }

    @Override
    public boolean canMapping() {
        // The library connects directly to the carrier with sockets protected via the
        // Protector fd-hook, so no dokodemo-door mapping is needed.
        return false;
    }

    @NotNull
    @Override
    public OlcrtcBean clone() {
        return KryoConverters.deserialize(new OlcrtcBean(), KryoConverters.serialize(this));
    }

    public static final Creator<OlcrtcBean> CREATOR = new CREATOR<OlcrtcBean>() {
        @NonNull
        @Override
        public OlcrtcBean newInstance() {
            return new OlcrtcBean();
        }

        @Override
        public OlcrtcBean[] newArray(int size) {
            return new OlcrtcBean[size];
        }
    };
}
