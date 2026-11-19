# 🛡️ Bloqueador Familiar

Aplicativo Android que bloqueia sites e apps de conteúdo adulto, imoral e impróprio — direto no aparelho, sem servidor, sem coleta de dados.

## 📱 Download

**[⬇️ bloqueador.apk](bloqueador.apk)** — baixe o APK e instale no Android.

## ⚠️ IMPORTANTE: Aviso sobre o Google Play Protect

Ao instalar, o **Google Play Protect pode bloquear a instalação** com a mensagem *"App não instalado — bloqueado por risco de fraude"*. Isso acontece porque o app usa Accessibility Service (recurso que apps maliciosos também usam), então o Google desconfia de APKs de desenvolvedor desconhecido.

**O app é seguro** — o código-fonte está aqui no repositório, auditável por qualquer um. Para instalar:

1. Play Store → foto do perfil → **Play Protect**
2. Engrenagem ⚙️ → desative **"Verificar apps com o Play Protect"**
3. Instale o `bloqueador.apk`
4. **Religue o Play Protect depois** de instalado

## 🚀 Como usar

1. Instale o APK
2. Abra o app → crie um **PIN de proteção** (4-8 dígitos)
3. Toque no escudo para ativar → autorize a permissão de VPN
4. Siga o **checklist de configuração** dentro do app:
   - VPN sempre ativa
   - Bloqueio de apps adultos (acessibilidade)
   - Desativar "instalar apps desconhecidos" nos navegadores
   - Filtro de conteúdo da Play Store

## 🔒 O que o app faz

- **VPN local de filtragem DNS** (IPv4 + IPv6): bloqueia ~150 domínios adultos + filtro de categorias do CleanBrowsing Family
- **Bloqueio de apps**: apps adultos conhecidos são fechados ao abrir (Accessibility Service)
- **PIN**: impede desligar a proteção sem senha
- **100% local**: nenhum dado sai do aparelho

## ⚠️ Limitações honestas

- Sites que usam IP fixo ou DNS criptografado (DoH) configurado manualmente podem contornar
- Mídia imprópria recebida diretamente por mensageiros não é filtrada
- "VPN sempre ativa" e bloqueio de desinstalação total exigem o modo Device Owner (comando `adb`, ver instruções no app)

## 🛠️ Build

Requer Flutter. O APK é assinado com keystore próprio (não incluído no repo por segurança — `key.properties` e `*.jks` estão no `.gitignore`).

```bash
flutter pub get
flutter build apk --release
```

## 📄 Licença

Uso pessoal. O código é aberto para auditoria.
