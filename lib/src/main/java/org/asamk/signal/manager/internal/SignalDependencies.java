package org.asamk.signal.manager.internal;

import org.asamk.signal.manager.config.ServiceConfig;
import org.asamk.signal.manager.config.ServiceEnvironmentConfig;
import org.signal.libsignal.metadata.certificate.CertificateValidator;
import org.signal.libsignal.zkgroup.profiles.ClientZkProfileOperations;
import org.whispersystems.signalservice.api.KeyBackupService;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.SignalServiceDataStore;
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.SignalSessionLock;
import org.whispersystems.signalservice.api.SignalWebSocket;
import org.whispersystems.signalservice.api.crypto.SignalServiceCipher;
import org.whispersystems.signalservice.api.groupsv2.ClientZkOperations;
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Api;
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Operations;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.services.ProfileService;
import org.whispersystems.signalservice.api.svr.SecureValueRecoveryV2;
import org.whispersystems.signalservice.api.util.CredentialsProvider;
import org.whispersystems.signalservice.api.util.UptimeSleepTimer;
import org.whispersystems.signalservice.api.websocket.WebSocketFactory;
import org.whispersystems.signalservice.internal.websocket.WebSocketConnection;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

public class SignalDependencies {

    private final Object LOCK = new Object();

    private final ServiceEnvironmentConfig serviceEnvironmentConfig;
    private final String userAgent;
    private final CredentialsProvider credentialsProvider;
    private final SignalServiceDataStore dataStore;
    private final ExecutorService executor;
    private final SignalSessionLock sessionLock;

    private boolean allowStories = true;

    private SignalServiceAccountManager accountManager;
    private GroupsV2Api groupsV2Api;
    private GroupsV2Operations groupsV2Operations;
    private ClientZkOperations clientZkOperations;

    private SignalWebSocket signalWebSocket;
    private SignalServiceMessageReceiver messageReceiver;
    private SignalServiceMessageSender messageSender;

    private KeyBackupService keyBackupService;
    private SecureValueRecoveryV2 secureValueRecoveryV2;
    private ProfileService profileService;
    private SignalServiceCipher cipher;

    SignalDependencies(
            final ServiceEnvironmentConfig serviceEnvironmentConfig,
            final String userAgent,
            final CredentialsProvider credentialsProvider,
            final SignalServiceDataStore dataStore,
            final ExecutorService executor,
            final SignalSessionLock sessionLock
    ) {
        this.serviceEnvironmentConfig = serviceEnvironmentConfig;
        this.userAgent = userAgent;
        this.credentialsProvider = credentialsProvider;
        this.dataStore = dataStore;
        this.executor = executor;
        this.sessionLock = sessionLock;
    }

    public void resetAfterAddressChange() {
        this.messageSender = null;
        this.cipher = null;
        getSignalWebSocket().forceNewWebSockets();
    }

    /**
     * This method needs to be called before the first websocket is created
     */
    public void setAllowStories(final boolean allowStories) {
        this.allowStories = allowStories;
    }

    public ServiceEnvironmentConfig getServiceEnvironmentConfig() {
        return serviceEnvironmentConfig;
    }

    public SignalSessionLock getSessionLock() {
        return sessionLock;
    }

    public SignalServiceAccountManager getAccountManager() {
        return getOrCreate(() -> accountManager,
                () -> accountManager = new SignalServiceAccountManager(serviceEnvironmentConfig.signalServiceConfiguration(),
                        credentialsProvider,
                        userAgent,
                        getGroupsV2Operations(),
                        ServiceConfig.AUTOMATIC_NETWORK_RETRY));
    }

    public SignalServiceAccountManager createUnauthenticatedAccountManager(String number, String password) {
        return new SignalServiceAccountManager(getServiceEnvironmentConfig().signalServiceConfiguration(),
                null,
                null,
                number,
                SignalServiceAddress.DEFAULT_DEVICE_ID,
                password,
                userAgent,
                ServiceConfig.AUTOMATIC_NETWORK_RETRY,
                ServiceConfig.GROUP_MAX_SIZE);
    }

    public GroupsV2Api getGroupsV2Api() {
        return getOrCreate(() -> groupsV2Api, () -> groupsV2Api = getAccountManager().getGroupsV2Api());
    }

    public GroupsV2Operations getGroupsV2Operations() {
        return getOrCreate(() -> groupsV2Operations,
                () -> groupsV2Operations = new GroupsV2Operations(ClientZkOperations.create(serviceEnvironmentConfig.signalServiceConfiguration()),
                        ServiceConfig.GROUP_MAX_SIZE));
    }

    private ClientZkOperations getClientZkOperations() {
        return getOrCreate(() -> clientZkOperations,
                () -> clientZkOperations = ClientZkOperations.create(serviceEnvironmentConfig.signalServiceConfiguration()));
    }

    private ClientZkProfileOperations getClientZkProfileOperations() {
        final var clientZkOperations = getClientZkOperations();
        return clientZkOperations == null ? null : clientZkOperations.getProfileOperations();
    }

    public SignalWebSocket getSignalWebSocket() {
        return getOrCreate(() -> signalWebSocket, () -> {
            final var timer = new UptimeSleepTimer();
            final var healthMonitor = new SignalWebSocketHealthMonitor(timer);
            final var webSocketFactory = new WebSocketFactory() {
                @Override
                public WebSocketConnection createWebSocket() {
                    return new WebSocketConnection("normal",
                            serviceEnvironmentConfig.signalServiceConfiguration(),
                            Optional.of(credentialsProvider),
                            userAgent,
                            healthMonitor,
                            allowStories);
                }

                @Override
                public WebSocketConnection createUnidentifiedWebSocket() {
                    return new WebSocketConnection("unidentified",
                            serviceEnvironmentConfig.signalServiceConfiguration(),
                            Optional.empty(),
                            userAgent,
                            healthMonitor,
                            allowStories);
                }
            };
            signalWebSocket = new SignalWebSocket(webSocketFactory);
            healthMonitor.monitor(signalWebSocket);
        });
    }

    public SignalServiceMessageReceiver getMessageReceiver() {
        return getOrCreate(() -> messageReceiver,
                () -> messageReceiver = new SignalServiceMessageReceiver(serviceEnvironmentConfig.signalServiceConfiguration(),
                        credentialsProvider,
                        userAgent,
                        getClientZkProfileOperations(),
                        ServiceConfig.AUTOMATIC_NETWORK_RETRY));
    }

    public SignalServiceMessageSender getMessageSender() {
        return getOrCreate(() -> messageSender,
                () -> messageSender = new SignalServiceMessageSender(serviceEnvironmentConfig.signalServiceConfiguration(),
                        credentialsProvider,
                        dataStore,
                        sessionLock,
                        userAgent,
                        getSignalWebSocket(),
                        Optional.empty(),
                        getClientZkProfileOperations(),
                        executor,
                        ServiceConfig.MAX_ENVELOPE_SIZE,
                        ServiceConfig.AUTOMATIC_NETWORK_RETRY));
    }

    public KeyBackupService getKeyBackupService() {
        return getOrCreate(() -> keyBackupService,
                () -> keyBackupService = getAccountManager().getKeyBackupService(ServiceConfig.getIasKeyStore(),
                        serviceEnvironmentConfig.keyBackupConfig().enclaveName(),
                        serviceEnvironmentConfig.keyBackupConfig().serviceId(),
                        serviceEnvironmentConfig.keyBackupConfig().mrenclave(),
                        10));
    }

    public SecureValueRecoveryV2 getSecureValueRecoveryV2() {
        return getOrCreate(() -> secureValueRecoveryV2,
                () -> secureValueRecoveryV2 = getAccountManager().getSecureValueRecoveryV2(serviceEnvironmentConfig.svr2Mrenclave()));
    }

    public Collection<KeyBackupService> getFallbackKeyBackupServices() {
        return serviceEnvironmentConfig.fallbackKeyBackupConfigs()
                .stream()
                .map(config -> getAccountManager().getKeyBackupService(ServiceConfig.getIasKeyStore(),
                        config.enclaveName(),
                        config.serviceId(),
                        config.mrenclave(),
                        10))
                .toList();
    }

    public ProfileService getProfileService() {
        return getOrCreate(() -> profileService,
                () -> profileService = new ProfileService(getClientZkProfileOperations(),
                        getMessageReceiver(),
                        getSignalWebSocket()));
    }

    public SignalServiceCipher getCipher() {
        return getOrCreate(() -> cipher, () -> {
            final var certificateValidator = new CertificateValidator(serviceEnvironmentConfig.unidentifiedSenderTrustRoot());
            final var address = new SignalServiceAddress(credentialsProvider.getAci(), credentialsProvider.getE164());
            final var deviceId = credentialsProvider.getDeviceId();
            cipher = new SignalServiceCipher(address, deviceId, dataStore.aci(), sessionLock, certificateValidator);
        });
    }

    private <T> T getOrCreate(Supplier<T> supplier, Callable creator) {
        var value = supplier.get();
        if (value != null) {
            return value;
        }

        synchronized (LOCK) {
            value = supplier.get();
            if (value != null) {
                return value;
            }
            creator.call();
            return supplier.get();
        }
    }

    private interface Callable {

        void call();
    }
}
