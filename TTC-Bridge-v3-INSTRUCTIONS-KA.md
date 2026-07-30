# TTC Bridge v3 — დაყენება და გამოყენება

## რეალური შესაძლებლობა

ეს APK ცალკე AI-ს არ იყენებს. ეკრანის კადრსა და შეზღუდულ ბრძანებებს ცვლის GitHub repository-ის მეშვეობით, ხოლო გადაწყვეტილებებს იღებს ეს ChatGPT ჩატი აქტიური საუბრის დროს.

ეს არ არის უწყვეტი 60 FPS remote desktop და ჩატი ფონურად თავისით არ იღვიძებს. თითოეულ ნაბიჯზე ჩატი კითხულობს ახალ კადრს, წერს ბრძანებას და ელოდება acknowledgement-ს. Puzzle/turn-based თამაშებისთვის ეს პრაქტიკულია; სწრაფ action თამაშებში — არა.

## დაყენება

1. დააყენე `TTC-Bridge-v3-debug.apk`.
2. შექმენი **ცალკე public GitHub repository**, რომელშიც სხვა ფაილები არ იქნება.
3. შექმენი fine-grained PAT მხოლოდ ამ repository-ზე და მისცე მხოლოდ `Contents: Read and write`.
4. აპში მიუთითე owner, repository, token, allowlisted package და polling interval.
5. ჩართე `TTC Bridge gestures` Accessibility settings-ში.
6. დააჭირე `Start bridge and screen capture` და დაადასტურე MediaProjection.
7. notification მუდმივად უნდა ჩანდეს. STOP ღილაკი მაშინვე წყვეტს bridge-ს.
8. ჩატში მომწერე runtime repository-ის `owner/repo` და სათამაშო package.

## v3 უსაფრთხოების ცვლილებები

- ყოველ გაშვებაზე იქმნება ახალი `session_id`; ძველი command აღარ შესრულდება.
- არა-allowlisted foreground-ზე რეალური screenshot არ იტვირთება — მხოლოდ privacy placeholder.
- Play Store/payment/wallet/bank/billing ეკრანებზე gesture იბლოკება.
- command error ერთხელ ACK-დება და უსასრულოდ აღარ მეორდება.
- ყველა მოქმედება და TTC event ტელეფონში JSONL-ად ინახება.

## TTC ბრძანებები

- `start_run` — სესიის დაწყება.
- `mark_level_start` — level timer-ის დაწყება.
- `mark_level_complete` — level timer-ის დასრულება.
- `pause_run` / `resume_run` — active TTC-დან პაუზის გამოკლება.
- `mark_event` — რეკლამა, retry, hint ან სხვა მოვლენა.
- `stop_run` — საბოლოო report.

GitHub-ში report არის `state/latest-ttc-report.json`, ხოლო ტელეფონში სრული JSONL ლოგებია:

`Android/data/com.ttclab.bridge/files/ttc_runs/`

## აუცილებელი შეზღუდვები

- არ გახსნა პირადი ჩატი, ბანკი, პაროლები ან სხვა მგრძნობიარე ეკრანი bridge-ის მუშაობისას.
- არ გამოიყენო სხვის მოწყობილობაზე ან scope-ის გარეთ.
- PAT არ ჩასვა ჩატში და screenshot-ში.
