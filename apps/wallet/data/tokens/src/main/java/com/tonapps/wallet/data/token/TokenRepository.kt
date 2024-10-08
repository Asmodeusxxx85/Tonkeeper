package com.tonapps.wallet.data.token

import android.content.Context
import androidx.collection.ArrayMap
import com.tonapps.wallet.api.API
import com.tonapps.wallet.api.entity.BalanceEntity
import com.tonapps.wallet.data.core.WalletCurrency
import com.tonapps.wallet.data.rates.RatesRepository
import com.tonapps.wallet.data.rates.entity.RatesEntity
import com.tonapps.wallet.data.token.entities.AccountTokenEntity
import com.tonapps.wallet.data.token.entities.TokenRateEntity
import com.tonapps.wallet.data.token.source.LocalDataSource
import com.tonapps.wallet.data.token.source.RemoteDataSource
import io.tonapi.models.TokenRates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class TokenRepository(
    private val context: Context,
    private val ratesRepository: RatesRepository,
    private val api: API
) {

    private val totalBalanceCache = ConcurrentHashMap<String, Float>(3, 1.0f, 2)

    private val localDataSource = LocalDataSource(context)
    private val remoteDataSource = RemoteDataSource(api)

    suspend fun getTotalBalances(
        currency: WalletCurrency,
        accountId: String,
        testnet: Boolean
    ): Float {
        return totalBalanceCache[cacheKey(accountId, testnet)] ?: loadTotalBalances(currency, accountId, testnet)
    }

    private suspend fun loadTotalBalances(
        currency: WalletCurrency,
        accountId: String,
        testnet: Boolean
    ): Float {
        val tokens = get(currency, accountId, testnet)
        var fiatBalance = 0f
        if (testnet) {
            fiatBalance = tokens.first().balance.value
        } else {
            for (token in tokens) {
                fiatBalance += token.fiat
            }
        }
        totalBalanceCache[cacheKey(accountId, testnet)] = fiatBalance
        return fiatBalance
    }

    suspend fun get(
        currency: WalletCurrency,
        accountId: String,
        testnet: Boolean
    ): List<AccountTokenEntity> {
        val tokens = getLocal(currency, accountId, testnet)
        if (tokens.isNotEmpty()) {
            return tokens
        }
        return getRemote(currency, accountId, testnet)
    }

    suspend fun getRemote(
        currency: WalletCurrency,
        accountId: String,
        testnet: Boolean
    ): List<AccountTokenEntity> = withContext(Dispatchers.IO) {
        val balances = load(currency, accountId, testnet)
        if (testnet) {
            return@withContext buildTokens(currency, balances, RatesEntity.empty(currency), true)
        }
        val rates = ratesRepository.getRates(currency, balances.map {
            it.token.address
        })
        buildTokens(currency, balances, rates, false)
    }

    suspend fun getLocal(
        currency: WalletCurrency,
        accountId: String,
        testnet: Boolean
    ): List<AccountTokenEntity> = withContext(Dispatchers.IO) {
        val balances = cache(accountId, testnet)
        if (testnet) {
            return@withContext buildTokens(currency, balances, RatesEntity.empty(currency), true)
        }

        val rates = ratesRepository.cache(currency, balances.map {
            it.token.address
        })
        if (rates.isEmpty) {
            emptyList()
        } else {
            buildTokens(currency, balances, rates, false)
        }
    }

    private fun buildTokens(
        currency: WalletCurrency,
        balances: List<BalanceEntity>,
        rates: RatesEntity,
        testnet: Boolean
    ): List<AccountTokenEntity> {
        val verified = mutableListOf<AccountTokenEntity>()
        val unverified = mutableListOf<AccountTokenEntity>()
        for (balance in balances) {
            val tokenAddress = balance.token.address
            val tokenRate = TokenRateEntity(
                currency = currency,
                fiat = rates.convert(tokenAddress, balance.value),
                rate = rates.getRate(tokenAddress),
                rateDiff24h = rates.getDiff24h(tokenAddress)
            )
            val token = AccountTokenEntity(
                balance = balance,
                rate = tokenRate
            )
            if (token.verified) {
                verified.add(token)
            } else {
                unverified.add(token)
            }
        }
        if (testnet) {
            return sortTestnet(verified + unverified)
        }
        return sort(verified) + sort(unverified)
    }

    private fun sort(list: List<AccountTokenEntity>): List<AccountTokenEntity> {
        return list.sortedWith { first, second ->
            when {
                first.isTon -> -1
                second.isTon -> 1
                else -> second.fiat.compareTo(first.fiat)
            }
        }
    }

    private fun sortTestnet(list: List<AccountTokenEntity>): List<AccountTokenEntity> {
        return list.sortedWith { first, second ->
            when {
                first.isTon -> -1
                second.isTon -> 1
                else -> second.balance.value.compareTo(first.balance.value)
            }
        }
    }

    private fun cache(
        accountId: String,
        testnet: Boolean
    ): List<BalanceEntity> {
        return localDataSource.getCache(cacheKey(accountId, testnet)) ?: emptyList()
    }

    private suspend fun load(
        currency: WalletCurrency,
        accountId: String,
        testnet: Boolean
    ): List<BalanceEntity> = withContext(Dispatchers.IO) {
        if (testnet) {
            val balances = remoteDataSource.load(currency, accountId, true)
            totalBalanceCache.remove(cacheKey(accountId, true))
            return@withContext balances
        }

        val rates = async { ratesRepository.load(currency, "TON") }
        val balances = remoteDataSource.load(currency, accountId, false)
        insertRates(currency, balances)
        localDataSource.setCache(cacheKey(accountId, false), balances)
        rates.await()
        totalBalanceCache.remove(cacheKey(accountId, false))
        balances
    }

    private fun cacheKey(accountId: String, testnet: Boolean): String {
        if (!testnet) {
            return accountId
        }
        return "${accountId}_testnet"
    }

    private fun insertRates(currency: WalletCurrency, list: List<BalanceEntity>) {
        val rates = ArrayMap<String, TokenRates>()
        for (balance in list) {
            balance.rates?.let {
                rates[balance.token.address] = it
            }
        }
        ratesRepository.insertRates(currency, rates)
    }

    // Move to separate repository

    fun simulateSwap(sendAddress: String, receiveAddress: String, units: String, isReverse: Boolean, slippageTolerance: String = "0.01" /*1%*/, referralAddress: String = ""): JSONObject {
        val method = if (isReverse) {
            "dex.reverse_simulate_swap"
        } else {
            "dex.simulate_swap"
        }
        val params = JSONObject().apply {
            put("ask_address", receiveAddress)
            if (isReverse) {
                put("ask_units", units)
            } else {
                put("offer_units", units)
            }
            put("offer_address", sendAddress)
            put("slippage_tolerance", slippageTolerance)
            if (referralAddress.isNotEmpty()) {
                put("referral_address", referralAddress)
            }
        }
        val response = api.stonfiRpc(method, params)
        val result = response.getJSONObject("result")

        return result
    }

    fun loadRawAssets(address: String? = null, testnet: Boolean): JSONArray {
        if (testnet) error("testnet not supported")

        val method = if (address.isNullOrEmpty()) {
            "asset.list"
        } else {
            "asset.balance_list"
        }
        val params = JSONObject().apply {
            address?.let {
                put("wallet_address", it)
            }
            put("load_community", false)
        }

        val response = api.stonfiRpc(method, params)
        val result = response.getJSONObject("result")
        val assets = result.getJSONArray("assets")

        return assets
    }

    fun loadMarketList(): JSONArray {
        val method = "market.list"
        val response = api.stonfiRpc(method)
        val result = response.getJSONObject("result")
        val pairs = result.getJSONArray("pairs")

        return pairs
    }
}