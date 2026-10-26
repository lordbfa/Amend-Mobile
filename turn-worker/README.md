# amend TURN service (Cloudflare)

দেশের বাইরে বা মোবাইল ডেটায় কল সংযোগ করতে amend এর একটা TURN সার্ভার লাগে। এই ছোট Cloudflare Worker টা অ্যাপকে Cloudflare এর বিশ্বজুড়ে ছড়ানো TURN নেটওয়ার্কের কয়েক ঘণ্টা মেয়াদি (৬ ঘণ্টা) credential দেয়। আসল API token থাকে শুধু Worker এর ভেতরে গোপনে, অ্যাপের ভেতরে কখনো যায় না।

- খরচ: প্রতি মাসে প্রথম **1,000 GB ফ্রি**। এতে কয়েকশো ঘণ্টা ভিডিও কল হয়। Worker এর ফ্রি প্ল্যানে দিনে ১ লাখ request চলে।
- গোপনীয়তা: TURN শুধু আগে থেকেই encrypted (DTLS-SRTP) কলটা আদান-প্রদান করে, কথা বা ছবি দেখতে পারে না।

## ১. TURN key বানান

1. https://dash.cloudflare.com এ লগইন করুন। ফ্রি অ্যাকাউন্টই যথেষ্ট।
2. বাঁ দিকের মেনু থেকে **Realtime → TURN Server → Create** চাপুন। নাম দিন `amend`।
3. এখন দুটো জিনিস দেখাবে: **Turn Token ID** আর **API Token**। দুটোই কপি করে রাখুন। API Token একবারই দেখায়।

## ২. Worker বানান (কোনো সফটওয়্যার ইনস্টল লাগবে না)

1. **Workers & Pages → Create → Create Worker** চাপুন, নাম দিন `amend-turn`, তারপর **Deploy**।
2. **Edit code** চাপুন। সেখানকার সব কোড মুছে এই ফোল্ডারের `worker.js` পুরোটা পেস্ট করুন, তারপর **Deploy**।
3. Worker এর **Settings → Variables and Secrets** এ তিনটা **Secret** যোগ করুন:
   | Name | Value |
   |---|---|
   | `TURN_KEY_ID` | ধাপ ১ এর Turn Token ID |
   | `TURN_KEY_API_TOKEN` | ধাপ ১ এর API Token |
   | `APP_KEY` | `amend-calls-v1` |
4. ব্রাউজারে `https://amend-turn.<আপনার-নাম>.workers.dev/health` খুলুন। `{"ok":true,...}` দেখালে সব ঠিক আছে।

## ৩. অ্যাপে যোগ করুন

দুটো উপায় আছে:
- **সব ইউজারের জন্য built-in:** Worker এর ঠিকানাটা `CallPrefs.DEFAULT_TURN_SERVICE` এ বসিয়ে নতুন বিল্ড দিন। তখন কাউকে কিছু সেট করতে হবে না।
- **শুধু নিজের ফোনে:** You → Calls → "TURN service address" এ ঠিকানাটা দিন, তারপর **Save** আর **Test** চাপুন।

চাইলে wrangler দিয়েও deploy করা যায়: `npx wrangler deploy`, তারপর `npx wrangler secret put TURN_KEY_ID` ইত্যাদি।
