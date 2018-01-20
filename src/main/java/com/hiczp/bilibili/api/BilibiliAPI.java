package com.hiczp.bilibili.api;

import com.hiczp.bilibili.api.interceptor.*;
import com.hiczp.bilibili.api.live.LiveService;
import com.hiczp.bilibili.api.live.socket.LiveClient;
import com.hiczp.bilibili.api.passport.PassportService;
import com.hiczp.bilibili.api.passport.entity.LoginResponseEntity;
import com.hiczp.bilibili.api.passport.entity.LogoutResponseEntity;
import com.hiczp.bilibili.api.passport.entity.RefreshTokenResponseEntity;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import javax.security.auth.login.LoginException;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;

//TODO 尚未实现自动 refreshToken 的拦截器
public class BilibiliAPI implements BilibiliServiceProvider, LiveClientProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(BilibiliAPI.class);

    private final Long apiInitTime = Instant.now().getEpochSecond();    //记录当前类被实例化的时间
    private final BilibiliClientProperties bilibiliClientProperties;
    private final BilibiliAccount bilibiliAccount;

    private PassportService passportService;
    private LiveService liveService;

    public BilibiliAPI() {
        this.bilibiliClientProperties = BilibiliClientProperties.defaultSetting();
        this.bilibiliAccount = BilibiliAccount.emptyInstance();
    }

    public BilibiliAPI(BilibiliClientProperties bilibiliClientProperties) {
        this.bilibiliClientProperties = bilibiliClientProperties;
        this.bilibiliAccount = BilibiliAccount.emptyInstance();
    }

    public BilibiliAPI(BilibiliAccount bilibiliAccount) {
        this.bilibiliClientProperties = BilibiliClientProperties.defaultSetting();
        this.bilibiliAccount = bilibiliAccount;
    }

    public BilibiliAPI(BilibiliClientProperties bilibiliClientProperties, BilibiliAccount bilibiliAccount) {
        this.bilibiliClientProperties = bilibiliClientProperties;
        this.bilibiliAccount = bilibiliAccount;
    }

    //TODO 不明确客户端访问 passport.bilibili.com 时使用的 UA
    @Override
    public PassportService getPassportService() {
        if (passportService == null) {
            LOGGER.debug("Init PassportService in BilibiliAPI instance {}", this.hashCode());
            OkHttpClient okHttpClient = new OkHttpClient().newBuilder()
                    .addInterceptor(new AddFixedParamsInterceptor(
                            "build", bilibiliClientProperties.getBuild(),
                            "mobi_app", "android",
                            "platform", "android"
                    ))
                    .addInterceptor(new AddDynamicParamsInterceptor(
                            () -> "ts", () -> Long.toString(Instant.now().getEpochSecond())
                    ))
                    .addInterceptor(new AddAppKeyInterceptor(bilibiliClientProperties))
                    .addInterceptor(new SortParamsAndSignInterceptor(bilibiliClientProperties))
                    .addInterceptor(new ErrorResponseConverterInterceptor())
                    .addNetworkInterceptor(new HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
                    .build();

            passportService = new Retrofit.Builder()
                    .baseUrl(BaseUrlDefinition.PASSPORT)
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(okHttpClient)
                    .build()
                    .create(PassportService.class);
        }
        return passportService;
    }

    @Override
    public LiveService getLiveService() {
        if (liveService == null) {
            LOGGER.debug("Init LiveService in BilibiliAPI instance {}", this.hashCode());
            OkHttpClient okHttpClient = new OkHttpClient.Builder()
                    .addInterceptor(new AddFixedHeadersInterceptor(
                            "Buvid", bilibiliClientProperties.getBuvId(),
                            "User-Agent", "Mozilla/5.0 BiliDroid/5.15.0 (bbcallen@gmail.com)",
                            "Device-ID", bilibiliClientProperties.getHardwareId()
                    )).addInterceptor(new AddDynamicHeadersInterceptor(
                            //Display-ID 的值在未登录前为 Buvid-客户端启动时间, 在登录后为 mid-客户端启动时间
                            () -> "Display-ID", () -> String.format("%s-%d", bilibiliAccount.getUserId() == null ? bilibiliClientProperties.getBuvId() : bilibiliAccount.getUserId(), apiInitTime)
                    )).addInterceptor(new AddFixedParamsInterceptor(
                            "_device", "android",
                            "_hwid", bilibiliClientProperties.getHardwareId(),
                            "build", bilibiliClientProperties.getBuild(),
                            "mobi_app", "android",
                            "platform", "android",
                            "scale", bilibiliClientProperties.getScale(),
                            "src", "google",
                            "version", bilibiliClientProperties.getVersion()
                    )).addInterceptor(new AddDynamicParamsInterceptor(
                            () -> "ts", () -> Long.toString(Instant.now().getEpochSecond()),
                            () -> "trace_id", () -> new SimpleDateFormat("yyyyMMddHHmm000ss").format(new Date())
                    ))
                    .addInterceptor(new AddAccessKeyInterceptor(bilibiliAccount))
                    .addInterceptor(new AddAppKeyInterceptor(bilibiliClientProperties))
                    .addInterceptor(new SortParamsAndSignInterceptor(bilibiliClientProperties))
                    .addInterceptor(new ErrorResponseConverterInterceptor())
                    .addNetworkInterceptor(new HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
                    .build();

            liveService = new Retrofit.Builder()
                    .baseUrl(BaseUrlDefinition.LIVE)
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(okHttpClient)
                    .build()
                    .create(LiveService.class);
        }
        return liveService;
    }

    public LoginResponseEntity login(String username, String password) throws IOException, LoginException {
        LOGGER.debug("Login attempting with username '{}'", username);
        LoginResponseEntity loginResponseEntity = BilibiliSecurityHelper.login(
                this,
                username,
                password
        );
        bilibiliAccount.copyFrom(loginResponseEntity.toBilibiliAccount());
        return loginResponseEntity;
    }

    public RefreshTokenResponseEntity refreshToken() throws IOException, LoginException {
        LOGGER.debug("RefreshToken attempting with userId '{}'", bilibiliAccount.getUserId());
        RefreshTokenResponseEntity refreshTokenResponseEntity = BilibiliSecurityHelper.refreshToken(
                this,
                bilibiliAccount.getAccessToken(),
                bilibiliAccount.getRefreshToken()
        );
        bilibiliAccount.copyFrom(refreshTokenResponseEntity.toBilibiliAccount());
        return refreshTokenResponseEntity;
    }

    public LogoutResponseEntity logout() throws IOException, LoginException {
        LOGGER.debug("Logout attempting with userId '{}'", bilibiliAccount.getUserId());
        Long userId = bilibiliAccount.getUserId();
        LogoutResponseEntity logoutResponseEntity = BilibiliSecurityHelper.logout(this, bilibiliAccount.getAccessToken());
        bilibiliAccount.reset();
        LOGGER.debug("Logout succeed with userId: {}", userId);
        return logoutResponseEntity;
    }

    @Override
    public LiveClient getLiveClient(long showRoomId) {
        return bilibiliAccount.getUserId() == null ? new LiveClient(showRoomId) : new LiveClient(showRoomId, bilibiliAccount.getUserId());
    }

    public BilibiliClientProperties getBilibiliClientProperties() {
        return bilibiliClientProperties;
    }

    public BilibiliAccount getBilibiliAccount() {
        return bilibiliAccount;
    }
}