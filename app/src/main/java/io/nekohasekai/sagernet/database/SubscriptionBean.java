package io.nekohasekai.sagernet.database;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import java.util.ArrayList;
import java.util.List;

import io.nekohasekai.sagernet.fmt.Serializable;

public class SubscriptionBean extends Serializable {

    public Integer type;
    public String link;
    public String token;
    public Boolean forceResolve;
    public Boolean deduplication;
    public Boolean updateWhenConnectedOnly;
    public String customUserAgent;
    public Boolean sendHwid;
    public String customHwidParams;
    public Boolean autoUpdate;
    public Integer autoUpdateDelay;
    public Integer lastUpdated;
    public Integer filterMode;
    public String filterRegex;

    // SIP008

    public Long bytesUsed;
    public Long bytesRemaining;

    // Open Online Config

    public String username;
    public Integer expiryDate;
    public List<String> protocols;


    // https://github.com/crossutility/Quantumult/blob/master/extra-subscription-feature.md

    public String subscriptionUserinfo;

    public SubscriptionBean() {
    }

    @Override
    public void serializeToBuffer(ByteBufferOutput output) {
        output.writeInt(3);

        output.writeInt(type);

        output.writeString(link);

        output.writeBoolean(forceResolve);
        output.writeBoolean(deduplication);
        output.writeBoolean(updateWhenConnectedOnly);
        output.writeString(customUserAgent);
        output.writeBoolean(autoUpdate);
        output.writeInt(autoUpdateDelay);
        output.writeInt(lastUpdated);

        output.writeString(subscriptionUserinfo);

        // v2
        output.writeInt(filterMode);
        output.writeString(filterRegex);

        // v3
        output.writeBoolean(sendHwid);
        output.writeString(customHwidParams);
    }

    public void serializeForShare(ByteBufferOutput output) {
        output.writeInt(1);

        output.writeInt(type);

        output.writeString(link);

        output.writeBoolean(forceResolve);
        output.writeBoolean(deduplication);
        output.writeBoolean(updateWhenConnectedOnly);
        output.writeString(customUserAgent);
        output.writeBoolean(sendHwid);
        output.writeString(customHwidParams);
    }

    @Override
    public void deserializeFromBuffer(ByteBufferInput input) {
        int version = input.readInt();

        type = input.readInt();
        link = input.readString();
        forceResolve = input.readBoolean();
        deduplication = input.readBoolean();
        updateWhenConnectedOnly = input.readBoolean();
        customUserAgent = input.readString();
        autoUpdate = input.readBoolean();
        autoUpdateDelay = input.readInt();
        lastUpdated = input.readInt();
        subscriptionUserinfo = input.readString();

        // v2
        if (version >= 2) {
            filterMode = input.readInt();
            filterRegex = input.readString();
        }
        if (version >= 3) {
            sendHwid = input.readBoolean();
            customHwidParams = input.readString();
        }
    }

    public void deserializeFromShare(ByteBufferInput input) {
        int version = input.readInt();

        type = input.readInt();
        link = input.readString();
        forceResolve = input.readBoolean();
        deduplication = input.readBoolean();
        updateWhenConnectedOnly = input.readBoolean();
        customUserAgent = input.readString();
        if (version >= 1) {
            sendHwid = input.readBoolean();
            customHwidParams = input.readString();
        }
    }

    @Override
    public void initializeDefaultValues() {
        if (type == null) type = 0;
        if (link == null) link = "";
        if (token == null) token = "";
        if (forceResolve == null) forceResolve = false;
        if (deduplication == null) deduplication = false;
        if (updateWhenConnectedOnly == null) updateWhenConnectedOnly = false;
        if (customUserAgent == null) customUserAgent = "";
        if (sendHwid == null) sendHwid = false;
        if (customHwidParams == null) customHwidParams = "";
        if (autoUpdate == null) autoUpdate = false;
        if (autoUpdateDelay == null) autoUpdateDelay = 1440;
        if (lastUpdated == null) lastUpdated = 0;
        if (filterMode == null) filterMode = 0;
        if (filterRegex == null) filterRegex = "";

        if (bytesUsed == null) bytesUsed = 0L;
        if (bytesRemaining == null) bytesRemaining = 0L;

        if (username == null) username = "";
        if (expiryDate == null) expiryDate = 0;
        if (protocols == null) protocols = new ArrayList<>();
    }

    public static final Creator<SubscriptionBean> CREATOR = new CREATOR<SubscriptionBean>() {
        @NonNull
        @Override
        public SubscriptionBean newInstance() {
            return new SubscriptionBean();
        }

        @Override
        public SubscriptionBean[] newArray(int size) {
            return new SubscriptionBean[size];
        }
    };

}
