/******************************************************************************
 * Copyright (C) 2026 by nekohasekai <contact-git@sekai.icu>                  *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.amneziawg;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

/**
 * AmneziaWG: an obfuscated WireGuard variant. Adds the AmneziaWG DPI-evasion
 * parameters (Jc/Jmin/Jmax, S1-S4, H1-H4, I1-I5) on top of the standard
 * WireGuard fields. Runs as a native sing-box "amneziawg" outbound.
 */
public class AmneziaWGBean extends AbstractBean {

    // Standard WireGuard fields.
    public String localAddress;
    public String privateKey;
    public String peerPublicKey;
    public String peerPreSharedKey;
    public Integer mtu;
    public String reserved;

    // AmneziaWG obfuscation parameters.
    public Integer jc;
    public Integer jmin;
    public Integer jmax;
    public Integer s1;
    public Integer s2;
    public Integer s3;
    public Integer s4;
    public String h1;
    public String h2;
    public String h3;
    public String h4;
    public String i1;
    public String i2;
    public String i3;
    public String i4;
    public String i5;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (localAddress == null) localAddress = "";
        if (privateKey == null) privateKey = "";
        if (peerPublicKey == null) peerPublicKey = "";
        if (peerPreSharedKey == null) peerPreSharedKey = "";
        if (mtu == null) mtu = 1420;
        if (reserved == null) reserved = "";
        if (jc == null) jc = 0;
        if (jmin == null) jmin = 0;
        if (jmax == null) jmax = 0;
        if (s1 == null) s1 = 0;
        if (s2 == null) s2 = 0;
        if (s3 == null) s3 = 0;
        if (s4 == null) s4 = 0;
        if (h1 == null) h1 = "";
        if (h2 == null) h2 = "";
        if (h3 == null) h3 = "";
        if (h4 == null) h4 = "";
        if (i1 == null) i1 = "";
        if (i2 == null) i2 = "";
        if (i3 == null) i3 = "";
        if (i4 == null) i4 = "";
        if (i5 == null) i5 = "";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(0);
        super.serialize(output);
        output.writeString(localAddress);
        output.writeString(privateKey);
        output.writeString(peerPublicKey);
        output.writeString(peerPreSharedKey);
        output.writeInt(mtu);
        output.writeString(reserved);
        output.writeInt(jc);
        output.writeInt(jmin);
        output.writeInt(jmax);
        output.writeInt(s1);
        output.writeInt(s2);
        output.writeInt(s3);
        output.writeInt(s4);
        output.writeString(h1);
        output.writeString(h2);
        output.writeString(h3);
        output.writeString(h4);
        output.writeString(i1);
        output.writeString(i2);
        output.writeString(i3);
        output.writeString(i4);
        output.writeString(i5);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        localAddress = input.readString();
        privateKey = input.readString();
        peerPublicKey = input.readString();
        peerPreSharedKey = input.readString();
        mtu = input.readInt();
        reserved = input.readString();
        jc = input.readInt();
        jmin = input.readInt();
        jmax = input.readInt();
        s1 = input.readInt();
        s2 = input.readInt();
        s3 = input.readInt();
        s4 = input.readInt();
        h1 = input.readString();
        h2 = input.readString();
        h3 = input.readString();
        h4 = input.readString();
        i1 = input.readString();
        i2 = input.readString();
        i3 = input.readString();
        i4 = input.readString();
        i5 = input.readString();
    }

    @Override
    public boolean canTCPing() {
        return false;
    }

    @NotNull
    @Override
    public AmneziaWGBean clone() {
        return KryoConverters.deserialize(new AmneziaWGBean(), KryoConverters.serialize(this));
    }

    public static final Creator<AmneziaWGBean> CREATOR = new CREATOR<AmneziaWGBean>() {
        @NonNull
        @Override
        public AmneziaWGBean newInstance() {
            return new AmneziaWGBean();
        }

        @Override
        public AmneziaWGBean[] newArray(int size) {
            return new AmneziaWGBean[size];
        }
    };
}
