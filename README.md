# FlashSale-Redis-System

This project is a high-concurrency flash sale backend system based on a voucher seckill scenario. It uses Redis and Lua scripting for atomic stock validation and one-user-one-order checks, Redis Stream for asynchronous order processing, Redisson distributed locks for duplicate-order prevention, and MySQL for final order persistence.
