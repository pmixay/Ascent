# Hackathon jury clarifications (November 2026)

Transcribed from forwarded Telegram messages shared by the team. The jury
answers were forwarded from Mikhail Grigorev; where a screenshot shows a date,
it is 6 November 2026, otherwise the date is not visible. Original Russian wording
is preserved verbatim, followed by an English rendering. Participant names are
omitted. The task itself is in [hackathon-brief-2026-11.pdf](hackathon-brief-2026-11.pdf).

## 1. Model storage and automatic verification (jury announcement, 20:49)

> По поводу хранения моделей
>
> Т.к. вас достаточно много, для автоматической проверки Apk должен содержать:
>
> 1) Встроенную модель
> 2) Возможно импорта аудиозаписи для анализа
> 3) Экспорт в json (в соответствии с тз в презентации)
>
> Функционал загрузки модели из внешней сети будет идти в карму для UX: вы можете предоставить 2 апк или дать возможность удалять встроенную модель

English: Because there are many teams, the APK must contain, for automatic
verification: (1) a built-in model, (2) audio import for analysis, and (3) JSON export
that follows the task's brief. Downloading a model from an external network counts
as a UX bonus ("karma"): a team may provide two APKs or let users delete the built-in model.

## 2. Transcription languages (20:52)

Question: Какие языки транскрибации должно поддерживать приложение?

Answer: Русский первым приоритетом. Английский вторым.

English: Russian is the first priority and English the second.

## 3. Load, audience and test emulator (21:34)

Question:

> 1. Даёт ли преимущество при оценке или доп. баллы за нагрузку на устройство, включая пиковую и среднюю?
> 2. Какая в итоге целевая аудитория конкретно? Всё же реал тайм звонки или умеренный темп для встреч и прочего?
> 3. Какая архитектура процессора будет на тестовом эмуляторе при проверке решений? x86 или ARM?

Answer:

> 1) Сделать на это акцент на защите лишним не будет, но при проверке работ именно нагрузку мы проверять не будем, опять же в силу ограниченного времени, только по указанным критериям
>
> 2) И та и другая ситуация возможны и одинаково интересны
>
> 3) арм, и почему только на эмуляторе)

English: (1) Emphasis on load during the defense cannot hurt, but load will not be
measured during checking because of limited time; only the stated criteria are used.
(2) Both real-time calls and moderate-pace meetings are possible and equally
interesting. (3) ARM, followed by the jury's question "and why only on the emulator?", whose
intent is not explained.

## 4. Whether both scenarios are required (21:34)

Question: Is there no direct need to implement both scenarios at once, and nothing
"in karma" comes from it?

Answer:

> Тест-кейсы будут разные
>
> Так что в этом смысле, это не "в карму", а прямой критерий

English: The test cases will differ, so in this sense it is not a bonus but a direct
criterion.

## 5. Input language (21:34)

Question: на каком языке должны быть записаны входные данные? только русский или будут использоваться и другие языки?

Answer: Русский первым приоритетом. Английский вторым.

English: Russian first priority, English second.

## Summary of requirements stated by the jury

- The verification APK embeds a model, imports an audio file and exports JSON in the
  schema from the brief.
- Network model download and removable built-in models are optional UX extras; two
  APKs are acceptable.
- Russian is the first language, English the second.
- Test emulator architecture is ARM.
- Test cases cover both real-time-call-like and meeting-like recordings, and this is
  a direct criterion. Device load is not measured.
