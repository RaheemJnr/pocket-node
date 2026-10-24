package com.rjnr.pocketnode.data.wallet

/** Fixed message: callers must use the foreground wallet reader, never cached dashboard authorization. */
class WalletSecretRequiresAuthenticationException : IllegalStateException("Foreground wallet authentication is required.")
