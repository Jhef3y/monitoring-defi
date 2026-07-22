/**
 * orca.js — execução LIVE na Orca (Whirlpools) via SDK oficial.
 *
 * SEGURANÇA: a chave privada NUNCA passa pelo navegador/banco. Ela é lida de
 * um keypair JSON no disco da VM (WALLET_KEYPAIR_PATH, montado read-only no
 * container). O front só exibe o endereço público.
 *
 * IMPORTANTE: este módulo é carregado sob demanda (import dinâmico) — o
 * executor funciona em modo paper mesmo sem as dependências live instaladas.
 * Antes de usar em produção, faça um smoke test com capital pequeno.
 *
 * Requisitos operacionais do modo live:
 *  - env RPC_URL (Alchemy) e WALLET_KEYPAIR_PATH válidos;
 *  - a carteira precisa ter os DOIS tokens do par (ex.: SOL e USDC) em
 *    proporção suficiente — o depósito num range usa os dois lados.
 */
import fs from 'fs';
import { liquidityForCapital } from './clmm.js';

let sdk = null;

async function loadSdk() {
  if (sdk) return sdk;
  const [whirl, common, anchor, web3, Decimal] = await Promise.all([
    import('@orca-so/whirlpools-sdk'),
    import('@orca-so/common-sdk'),
    import('@coral-xyz/anchor'),
    import('@solana/web3.js'),
    import('decimal.js'),
  ]);
  sdk = { whirl, common, anchor, web3, Decimal: Decimal.default };
  return sdk;
}

export function loadKeypairSync(path) {
  const raw = JSON.parse(fs.readFileSync(path, 'utf8'));
  return raw; // array de bytes (formato id.json do solana-keygen)
}

async function context() {
  const { whirl, anchor, web3 } = await loadSdk();
  const rpcUrl = process.env.RPC_URL;
  const keyPath = process.env.WALLET_KEYPAIR_PATH;
  if (!rpcUrl || !keyPath) {
    throw new Error('modo live exige RPC_URL e WALLET_KEYPAIR_PATH');
  }
  const secret = Uint8Array.from(loadKeypairSync(keyPath));
  const keypair = web3.Keypair.fromSecretKey(secret);
  const connection = new web3.Connection(rpcUrl, 'confirmed');
  const wallet = new anchor.Wallet(keypair);
  const provider = new anchor.AnchorProvider(connection, wallet, { commitment: 'confirmed' });
  const ctx = whirl.WhirlpoolContext.withProvider(provider, whirl.ORCA_WHIRLPOOL_PROGRAM_ID);
  const client = whirl.buildWhirlpoolClient(ctx);
  return { ctx, client, connection, keypair, wallet };
}

export async function walletInfo() {
  const { web3 } = await loadSdk();
  const { connection, keypair } = await context();
  const lamports = await connection.getBalance(keypair.publicKey);
  return { pubkey: keypair.publicKey.toBase58(), sol: lamports / web3.LAMPORTS_PER_SOL };
}

/**
 * Abre uma posição concentrada [rangeLow, rangeHigh] com ~capitalUsd.
 * Retorna { positionMint, txSignature, amountA, amountB }.
 */
export async function openPosition(poolAddress, rangeLow, rangeHigh, capitalUsd, slippageBps) {
  const { whirl, common, web3, Decimal } = await loadSdk();
  const { client } = await context();

  const pool = await client.getPool(new web3.PublicKey(poolAddress));
  const data = pool.getData();
  const tokenA = pool.getTokenAInfo();       // ex.: SOL
  const tokenB = pool.getTokenBInfo();       // ex.: USDC
  const tickSpacing = data.tickSpacing;

  const lowerTick = whirl.TickUtil.getInitializableTickIndex(
    whirl.PriceMath.priceToTickIndex(new Decimal(rangeLow), tokenA.decimals, tokenB.decimals),
    tickSpacing);
  const upperTick = whirl.TickUtil.getInitializableTickIndex(
    whirl.PriceMath.priceToTickIndex(new Decimal(rangeHigh), tokenA.decimals, tokenB.decimals),
    tickSpacing);

  // Quanto do capital entra no lado B (USDC)? Usa a matemática CLMM local.
  const price = whirl.PriceMath.sqrtPriceX64ToPrice(
    data.sqrtPrice, tokenA.decimals, tokenB.decimals).toNumber();
  const { amountB } = liquidityForCapital(capitalUsd, price, rangeLow, rangeHigh);

  const slippage = common.Percentage.fromFraction(slippageBps, 10000);
  const inputB = new Decimal(amountB.toFixed(Math.min(6, tokenB.decimals)));

  const quote = whirl.increaseLiquidityQuoteByInputTokenUsingPriceSlippage(
    tokenB.mint, inputB, lowerTick, upperTick, slippage, pool);

  const { positionMint, tx } = await pool.openPosition(lowerTick, upperTick, quote);
  const txSignature = await tx.buildAndExecute();

  const toUi = (bn, dec) => Number(bn.toString()) / 10 ** dec;
  return {
    positionMint: positionMint.toBase58(),
    txSignature,
    amountA: toUi(quote.tokenEstA, tokenA.decimals),
    amountB: toUi(quote.tokenEstB, tokenB.decimals),
  };
}

/**
 * Fecha a posição (remove liquidez + coleta fees + fecha a conta).
 * Mede os amounts recebidos por diferença de saldo da carteira (inclui fees).
 * Retorna { txSignatures, amountA, amountB }.
 */
export async function closePosition(poolAddress, positionMintB58, slippageBps) {
  const { whirl, common, web3 } = await loadSdk();
  const { ctx, client, connection, keypair } = await context();
  const spl = await import('@solana/spl-token');

  const pool = await client.getPool(new web3.PublicKey(poolAddress));
  const tokenA = pool.getTokenAInfo();
  const tokenB = pool.getTokenBInfo();
  const positionMint = new web3.PublicKey(positionMintB58);
  const positionPda = whirl.PDAUtil.getPosition(ctx.program.programId, positionMint);

  const balance = async (mint, decimals) => {
    const WSOL = 'So11111111111111111111111111111111111111112';
    if (mint.toBase58() === WSOL) {
      return (await connection.getBalance(keypair.publicKey)) / web3.LAMPORTS_PER_SOL;
    }
    const ata = spl.getAssociatedTokenAddressSync(mint, keypair.publicKey);
    try {
      const b = await connection.getTokenAccountBalance(ata);
      return Number(b.value.uiAmount || 0);
    } catch { return 0; }
  };

  const beforeA = await balance(tokenA.mint, tokenA.decimals);
  const beforeB = await balance(tokenB.mint, tokenB.decimals);

  const slippage = common.Percentage.fromFraction(slippageBps, 10000);
  const txs = await pool.closePosition(positionPda.publicKey, slippage);
  const txSignatures = [];
  for (const tx of txs) txSignatures.push(await tx.buildAndExecute());

  const afterA = await balance(tokenA.mint, tokenA.decimals);
  const afterB = await balance(tokenB.mint, tokenB.decimals);

  return {
    txSignatures,
    amountA: Math.max(afterA - beforeA, 0),   // inclui fees coletadas
    amountB: Math.max(afterB - beforeB, 0),
  };
}
