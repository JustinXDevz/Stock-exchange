# StockExchange (Paper 26.2)

Plugin bursa saham untuk Paper 26.2: saham sistem buatan admin + perusahaan buatan pemain (IPO, kas, dividen, delisting).

Butuh: Paper 26.2, Java 25, [Vault](https://www.spigotmc.org/resources/vault.34315/) + plugin ekonomi (EssentialsX dll).

## Build otomatis (GitHub Actions)
1. Buat repo baru di GitHub, upload semua isi folder ini (termasuk `.github/`).
2. Buka tab **Actions** -> workflow **Build** berjalan otomatis saat push.
3. Setelah hijau, buka run-nya -> bagian **Artifacts** -> unduh `StockExchange-jar` (isinya `StockExchange-1.0.0.jar`).
4. Untuk rilis: buat tag `v1.0.0` -> jar otomatis terlampir di halaman **Releases**.

Build lokal: `mvn clean package` (JDK 25) -> `target/StockExchange-1.0.0.jar`.

## Command
Pemain: `/stock` (GUI), `/stock buy|sell <ticker> <jumlah>`, `/stock price <ticker>`, `/stock portfolio|top|news|history`
Perusahaan: `/company create <ticker> <totalSaham> <nama>`, `/company ipo <ticker> <saham> <harga> <jam>`,
`/company deposit|dividend <ticker> <jumlah>`, `/company info|list|icon|desc|delist`
Admin (`stockexchange.admin`): `/stockadmin create <ticker> <harga> <nama>`, `delete`, `setprice`, `setvol`, `seticon`,
`event <boom|crash> <ticker>`, `freeze`, `unfreeze`, `reload`, `list`

## Cara kerja singkat
- Pasar memakai **market maker**: pemain beli/jual ke pasar, bukan antar pemain. Beli dibatasi stok `poolShares`.
- Harga bergerak karena transaksi (price impact), fluktuasi acak, event, dan (perusahaan pemain) tarikan ke nilai buku kas.
- IPO: saham pendiri ditahan sampai IPO selesai. Terjual >= `min-sold-percent` -> sukses (dana masuk kas), kalau tidak -> refund.
- Kas tidak bisa ditarik pendiri; keluar hanya lewat dividen atau likuidasi.
- Fee transaksi dan biaya pendirian/listing = money sink.
- Data di SQLite (`plugins/StockExchange/stockexchange.db`).

## Integrasi EconomyShopGUI
Pasang EconomyShopGUI (gratis/premium). Plugin ini mendengarkan `PostTransactionEvent` miliknya.
- **Harga item shop naik-turun**: diatur oleh fitur *Dynamic Pricing* bawaan EconomyShopGUI (di wiki-nya, pengaturan per section ditandai Premium, jadi cek versimu).
- **Saham sektor ikut volume shop**: di `config.yml` -> `shop.sectors.<id>.stock` isi ticker saham sistem. Beli dari shop = tekanan naik, jual ke shop = tekanan turun.
- **Omzet shop ke kas perusahaan**: pemilik memilih sektor lewat `/company sector <ticker> <sektor>`. Sebagian kecil nilai transaksi sektor itu (`shop.revenue.share-percent`) masuk kas (dibagi rata antar perusahaan di sektor yang sama, ada batas harian). Ini uang baru dari server, jadi jaga angkanya kecil.
- Sektor dicocokkan lewat token item/section (mis. `DIAMOND_ORE` -> `DIAMOND`, `ORE`).
- Event dibaca lewat reflection karena nama method tidak terverifikasi. Cek dengan `/stockadmin shop` (status) dan `/stockadmin shopdebug` (log isi event ke console). Kalau nilai/jenis transaksi tidak terbaca, kirim log debug-nya untuk disesuaikan.

## Belum ada di versi ini
Limit order, alert harga, voting pemegang saham, stock split/buyback, jabatan karyawan, PlaceholderAPI.
