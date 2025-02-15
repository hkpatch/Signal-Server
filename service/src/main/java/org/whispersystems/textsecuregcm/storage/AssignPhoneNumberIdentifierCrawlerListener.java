/*
 * Copyright 2013-2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.storage;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.whispersystems.textsecuregcm.metrics.MetricsUtil.name;

// TODO Remove this crawler when PNIs have been assigned to all existing accounts
public class AssignPhoneNumberIdentifierCrawlerListener extends AccountDatabaseCrawlerListener {

  private final AccountsManager accountsManager;
  private final PhoneNumberIdentifiers phoneNumberIdentifiers;

  private final ExecutorService updateExecutor = Executors.newFixedThreadPool(16);

  private static final Counter ASSIGNED_PNI_COUNTER =
      Metrics.counter(name(AssignPhoneNumberIdentifierCrawlerListener.class, "assignPni"));

  public AssignPhoneNumberIdentifierCrawlerListener(final AccountsManager accountsManager,
      final PhoneNumberIdentifiers phoneNumberIdentifiers) {

    this.accountsManager = accountsManager;
    this.phoneNumberIdentifiers = phoneNumberIdentifiers;
  }

  @Override
  public void onCrawlStart() {
  }

  @Override
  public void onCrawlEnd(final Optional<UUID> fromUuid) {
  }

  @Override
  protected void onCrawlChunk(final Optional<UUID> fromUuid, final List<Account> chunkAccounts) {
    // There are exactly two ways an account can get a phone number identifier (PNI):
    //
    // 1. It's assigned at construction time for accounts created after the introduction of PNIs
    // 2. It's assigned by this crawler
    //
    // That means that we don't need to worry about accidentally overwriting a PNI assigned by another source; if an
    // account doesn't have a PNI when it winds up in a crawled chunk, there's no danger that it will have one after a
    // refresh, and so we can blindly assign a random PNI.
    CompletableFuture.allOf(chunkAccounts.stream()
        .filter(account -> account.getPhoneNumberIdentifier().isEmpty())
        .map(Account::getUuid)
        .map(accountsManager::getByAccountIdentifier)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .map(accountWithoutPni -> CompletableFuture.runAsync(() -> {
          final String number = accountWithoutPni.getNumber();
          final UUID phoneNumberIdentifier = phoneNumberIdentifiers.getPhoneNumberIdentifier(number);

          accountsManager.update(accountWithoutPni, a -> a.setNumber(number, phoneNumberIdentifier));

          ASSIGNED_PNI_COUNTER.increment();
        }, updateExecutor))
        .toArray(CompletableFuture[]::new)).join();
  }
}
