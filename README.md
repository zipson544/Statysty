# AnarchiaStats (Paper 1.21.4)
- `/stats [nick]` – GUI ze statystykami
- `/klan info [skrot]` – info o klanie (czyta plugins/AnarchiaKlany/klany.yml)
- `/statsadmin <case|spent|earned> <nick> [ilosc]` – dla getcase / sklepu (konsola lub OP)
- Skrzynki: liczone z komunikatu getcase na czacie (config.yml: case-message-regex), awaryjnie `/statsadmin case`
- Wydane/zarobione: przez Vault, tylko gdy gracz jest w /sklep (config.yml: shop-commands)

Build: push na GitHub -> Actions -> artefakt AnarchiaStats.jar (albo `mvn package`).
