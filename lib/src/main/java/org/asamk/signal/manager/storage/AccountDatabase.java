package org.asamk.signal.manager.storage;

import com.zaxxer.hikari.HikariDataSource;

import org.asamk.signal.manager.storage.groups.GroupStore;
import org.asamk.signal.manager.storage.identities.IdentityKeyStore;
import org.asamk.signal.manager.storage.prekeys.PreKeyStore;
import org.asamk.signal.manager.storage.prekeys.SignedPreKeyStore;
import org.asamk.signal.manager.storage.recipients.RecipientStore;
import org.asamk.signal.manager.storage.sendLog.MessageSendLogStore;
import org.asamk.signal.manager.storage.senderKeys.SenderKeyRecordStore;
import org.asamk.signal.manager.storage.senderKeys.SenderKeySharedStore;
import org.asamk.signal.manager.storage.sessions.SessionStore;
import org.asamk.signal.manager.storage.stickers.StickerStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;

public class AccountDatabase extends Database {

    private final static Logger logger = LoggerFactory.getLogger(AccountDatabase.class);
    private static final long DATABASE_VERSION = 9;

    private AccountDatabase(final HikariDataSource dataSource) {
        super(logger, DATABASE_VERSION, dataSource);
    }

    public static AccountDatabase init(File databaseFile) throws SQLException {
        return initDatabase(databaseFile, AccountDatabase::new);
    }

    @Override
    protected void createDatabase(final Connection connection) throws SQLException {
        RecipientStore.createSql(connection);
        MessageSendLogStore.createSql(connection);
        StickerStore.createSql(connection);
        PreKeyStore.createSql(connection);
        SignedPreKeyStore.createSql(connection);
        GroupStore.createSql(connection);
        SessionStore.createSql(connection);
        IdentityKeyStore.createSql(connection);
        SenderKeyRecordStore.createSql(connection);
        SenderKeySharedStore.createSql(connection);
    }

    @Override
    protected void upgradeDatabase(final Connection connection, final long oldVersion) throws SQLException {
        if (oldVersion < 2) {
            logger.debug("Updating database: Creating recipient table");
            try (final var statement = connection.createStatement()) {
                statement.executeUpdate("""
                                        CREATE TABLE recipient (
                                          _id INTEGER PRIMARY KEY AUTOINCREMENT,
                                          number TEXT UNIQUE,
                                          uuid BLOB UNIQUE,
                                          profile_key BLOB,
                                          profile_key_credential BLOB,

                                          given_name TEXT,
                                          family_name TEXT,
                                          color TEXT,

                                          expiration_time INTEGER NOT NULL DEFAULT 0,
                                          blocked BOOLEAN NOT NULL DEFAULT FALSE,
                                          archived BOOLEAN NOT NULL DEFAULT FALSE,
                                          profile_sharing BOOLEAN NOT NULL DEFAULT FALSE,

                                          profile_last_update_timestamp INTEGER NOT NULL DEFAULT 0,
                                          profile_given_name TEXT,
                                          profile_family_name TEXT,
                                          profile_about TEXT,
                                          profile_about_emoji TEXT,
                                          profile_avatar_url_path TEXT,
                                          profile_mobile_coin_address BLOB,
                                          profile_unidentified_access_mode TEXT,
                                          profile_capabilities TEXT
                                        );
                                        """);
            }
        }
        if (oldVersion < 3) {
            logger.debug("Updating database: Creating sticker table");
            try (final var statement = connection.createStatement()) {
                statement.executeUpdate("""
                                        CREATE TABLE sticker (
                                          _id INTEGER PRIMARY KEY,
                                          pack_id BLOB UNIQUE NOT NULL,
                                          pack_key BLOB NOT NULL,
                                          installed BOOLEAN NOT NULL DEFAULT FALSE
                                        );
                                        """);
            }
        }
        if (oldVersion < 4) {
            logger.debug("Updating database: Creating pre key tables");
            try (final var statement = connection.createStatement()) {
                statement.executeUpdate("""
                                        CREATE TABLE signed_pre_key (
                                          _id INTEGER PRIMARY KEY,
                                          account_id_type INTEGER NOT NULL,
                                          key_id INTEGER NOT NULL,
                                          public_key BLOB NOT NULL,
                                          private_key BLOB NOT NULL,
                                          signature BLOB NOT NULL,
                                          timestamp INTEGER DEFAULT 0,
                                          UNIQUE(account_id_type, key_id)
                                        );
                                        CREATE TABLE pre_key (
                                          _id INTEGER PRIMARY KEY,
                                          account_id_type INTEGER NOT NULL,
                                          key_id INTEGER NOT NULL,
                                          public_key BLOB NOT NULL,
                                          private_key BLOB NOT NULL,
                                          UNIQUE(account_id_type, key_id)
                                        );
                                        """);
            }
        }
        if (oldVersion < 5) {
            logger.debug("Updating database: Creating group tables");
            try (final var statement = connection.createStatement()) {
                statement.executeUpdate("""
                                        CREATE TABLE group_v2 (
                                          _id INTEGER PRIMARY KEY,
                                          group_id BLOB UNIQUE NOT NULL,
                                          master_key BLOB NOT NULL,
                                          group_data BLOB,
                                          distribution_id BLOB UNIQUE NOT NULL,
                                          blocked BOOLEAN NOT NULL DEFAULT FALSE,
                                          permission_denied BOOLEAN NOT NULL DEFAULT FALSE
                                        );
                                        CREATE TABLE group_v1 (
                                          _id INTEGER PRIMARY KEY,
                                          group_id BLOB UNIQUE NOT NULL,
                                          group_id_v2 BLOB UNIQUE,
                                          name TEXT,
                                          color TEXT,
                                          expiration_time INTEGER NOT NULL DEFAULT 0,
                                          blocked BOOLEAN NOT NULL DEFAULT FALSE,
                                          archived BOOLEAN NOT NULL DEFAULT FALSE
                                        );
                                        CREATE TABLE group_v1_member (
                                          _id INTEGER PRIMARY KEY,
                                          group_id INTEGER NOT NULL REFERENCES group_v1 (_id) ON DELETE CASCADE,
                                          recipient_id INTEGER NOT NULL REFERENCES recipient (_id) ON DELETE CASCADE,
                                          UNIQUE(group_id, recipient_id)
                                        );
                                        """);
            }
        }
        if (oldVersion < 6) {
            logger.debug("Updating database: Creating session tables");
            try (final var statement = connection.createStatement()) {
                statement.executeUpdate("""
                                        CREATE TABLE session (
                                          _id INTEGER PRIMARY KEY,
                                          account_id_type INTEGER NOT NULL,
                                          recipient_id INTEGER NOT NULL REFERENCES recipient (_id) ON DELETE CASCADE,
                                          device_id INTEGER NOT NULL,
                                          record BLOB NOT NULL,
                                          UNIQUE(account_id_type, recipient_id, device_id)
                                        );
                                        """);
            }
        }
        if (oldVersion < 7) {
            logger.debug("Updating database: Creating identity table");
            try (final var statement = connection.createStatement()) {
                statement.executeUpdate("""
                                        CREATE TABLE identity (
                                          _id INTEGER PRIMARY KEY,
                                          recipient_id INTEGER UNIQUE NOT NULL REFERENCES recipient (_id) ON DELETE CASCADE,
                                          identity_key BLOB NOT NULL,
                                          added_timestamp INTEGER NOT NULL,
                                          trust_level INTEGER NOT NULL
                                        );
                                        """);
            }
        }
        if (oldVersion < 8) {
            logger.debug("Updating database: Creating sender key tables");
            try (final var statement = connection.createStatement()) {
                statement.executeUpdate("""
                                        CREATE TABLE sender_key (
                                          _id INTEGER PRIMARY KEY,
                                          recipient_id INTEGER NOT NULL REFERENCES recipient (_id) ON DELETE CASCADE,
                                          device_id INTEGER NOT NULL,
                                          distribution_id BLOB NOT NULL,
                                          record BLOB NOT NULL,
                                          created_timestamp INTEGER NOT NULL,
                                          UNIQUE(recipient_id, device_id, distribution_id)
                                        );
                                        CREATE TABLE sender_key_shared (
                                          _id INTEGER PRIMARY KEY,
                                          recipient_id INTEGER NOT NULL REFERENCES recipient (_id) ON DELETE CASCADE,
                                          device_id INTEGER NOT NULL,
                                          distribution_id BLOB NOT NULL,
                                          timestamp INTEGER NOT NULL,
                                          UNIQUE(recipient_id, device_id, distribution_id)
                                        );
                                        """);
            }
        }
        if (oldVersion < 9) {
            logger.debug("Updating database: Adding urgent field");
            try (final var statement = connection.createStatement()) {
                statement.executeUpdate("""
                                        ALTER TABLE message_send_log_content ADD COLUMN urgent BOOLEAN NOT NULL DEFAULT TRUE;
                                        """);
            }
        }
    }
}
