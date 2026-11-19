import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'apps_page.dart';

void main() => runApp(const BloqueadorApp());

class BloqueadorApp extends StatelessWidget {
  const BloqueadorApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Bloqueador Familiar',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF16A34A),
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});
  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  static const _ch = MethodChannel('com.bloqueador.app/vpn');

  bool _loading = true;
  bool _hasPin = false;
  bool _vpnOn = false;
  bool _isOwner = false;
  bool _accOn = false;
  int _blocked = 0;
  int _appBlocked = 0;
  String? _vpnError;
  int _dnsSeen = 0;
  String _activeDns = '';
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    _init();
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  Future<void> _init() async {
    bool hasPin = false;
    bool isOwner = false;
    try {
      hasPin = await _ch.invokeMethod<bool>('hasPin') ?? false;
      isOwner = await _ch.invokeMethod<bool>('isDeviceOwner') ?? false;
      _accOn = await _ch.invokeMethod<bool>('isAccessibilityEnabled') ?? false;
    } catch (_) {}
    setState(() {
      _hasPin = hasPin;
      _isOwner = isOwner;
      _loading = false;
    });
    if (!hasPin) _askSetupPin();
    _refresh();
    _poll = Timer.periodic(const Duration(seconds: 5), (_) => _refresh());
  }

  Future<void> _refresh() async {
    try {
      final st = await _ch.invokeMethod<Map>('stats');
      if (st == null) return;
      final running = st['running'] == true;
      final count = (st['blocked'] as num?)?.toInt() ?? 0;
      final appCount = (st['appBlocked'] as num?)?.toInt() ?? 0;
      final acc = st['accOn'] == true;
      final err = (st['error'] as String?)?.isNotEmpty == true ? st['error'] as String : null;
      final dns = (st['dnsSeen'] as num?)?.toInt() ?? 0;
      final dnsList = (st['activeDns'] as String?) ?? '';
      if (mounted && (running != _vpnOn || count != _blocked || appCount != _appBlocked || acc != _accOn || err != _vpnError || dns != _dnsSeen || dnsList != _activeDns)) {
        setState(() { _vpnOn = running; _blocked = count; _appBlocked = appCount; _accOn = acc; _vpnError = err; _dnsSeen = dns; _activeDns = dnsList; });
      }
    } catch (_) {}
  }

  Future<void> _askSetupPin() async {
    final pin = await _pinDialog(title: 'Crie um PIN de proteção', confirm: true);
    if (pin == null || pin.length < 4) return;
    final ok = await _ch.invokeMethod<bool>('setPin', {'pin': pin}) ?? false;
    if (ok && mounted) {
      setState(() => _hasPin = true);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('PIN criado! Agora ninguém desliga sem ele.')),
      );
    }
  }

  Future<String?> _pinDialog({required String title, bool confirm = false}) async {
    final ctrl = TextEditingController();
    final ctrl2 = TextEditingController();
    return showDialog<String>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: ctrl,
              keyboardType: TextInputType.number,
              obscureText: true,
              maxLength: 8,
              decoration: const InputDecoration(labelText: 'PIN (4 a 8 dígitos)'),
            ),
            if (confirm)
              TextField(
                controller: ctrl2,
                keyboardType: TextInputType.number,
                obscureText: true,
                maxLength: 8,
                decoration: const InputDecoration(labelText: 'Repita o PIN'),
              ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancelar')),
          FilledButton(
            onPressed: () {
              if (ctrl.text.length < 4) return;
              if (confirm && ctrl.text != ctrl2.text) return;
              Navigator.pop(ctx, ctrl.text);
            },
            child: const Text('OK'),
          ),
        ],
      ),
    );
  }

  Future<bool> _askPin() async {
    final pin = await _pinDialog(title: 'Digite o PIN para desligar');
    if (pin == null) return false;
    return await _ch.invokeMethod<bool>('checkPin', {'pin': pin}) ?? false;
  }

  Future<void> _toggle() async {
    if (_vpnOn) {
      // desligar exige PIN
      if (!await _askPin()) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('PIN incorreto.')),
          );
        }
        return;
      }
      await _ch.invokeMethod('stopVpn');
      _refresh();
      return;
    }
    final res = await _ch.invokeMethod<String>('prepareVpn');
    if (res == 'denied' && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Permissão de VPN negada. Sem ela o bloqueio não funciona.')),
      );
    }
    _refresh();
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    final theme = Theme.of(context);
    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: SingleChildScrollView(
            child: Column(
            children: [
              const SizedBox(height: 24),
              Icon(
                Icons.shield_outlined,
                size: 72,
                color: _vpnOn ? Colors.greenAccent : theme.colorScheme.outline,
              ),
              const SizedBox(height: 12),
              Text(
                _vpnOn ? 'PROTEÇÃO ATIVA' : 'PROTEÇÃO DESLIGADA',
                style: theme.textTheme.titleLarge?.copyWith(
                  fontWeight: FontWeight.w800,
                  letterSpacing: 1.2,
                  color: _vpnOn ? Colors.greenAccent : theme.colorScheme.outline,
                ),
              ),
              const SizedBox(height: 6),
              Text(
                _vpnOn
                    ? 'Sites e apps adultos estão bloqueados neste aparelho.'
                    : 'Toque no escudo para ativar o bloqueio.',
                textAlign: TextAlign.center,
                style: theme.textTheme.bodyMedium?.copyWith(color: Colors.white70),
              ),
              if (_vpnError != null) ...[
                const SizedBox(height: 10),
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: Colors.red.withOpacity(0.12),
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: Colors.red.withOpacity(0.4)),
                  ),
                  child: Text(
                    'Erro VPN: $_vpnError',
                    style: const TextStyle(fontSize: 12, color: Colors.redAccent),
                  ),
                ),
              ],
              const SizedBox(height: 20),

              // botão grande
              GestureDetector(
                onTap: _toggle,
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 250),
                  width: 170,
                  height: 170,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: _vpnOn
                        ? Colors.green.withOpacity(0.15)
                        : theme.colorScheme.surfaceContainerHighest,
                    border: Border.all(
                      color: _vpnOn ? Colors.greenAccent : theme.colorScheme.outline,
                      width: 4,
                    ),
                    boxShadow: _vpnOn
                        ? [BoxShadow(color: Colors.green.withOpacity(0.35), blurRadius: 40, spreadRadius: 4)]
                        : null,
                  ),
                  child: Icon(
                    _vpnOn ? Icons.lock : Icons.lock_open,
                    size: 64,
                    color: _vpnOn ? Colors.greenAccent : theme.colorScheme.outline,
                  ),
                ),
              ),

              const SizedBox(height: 24),

              // contador
              Card(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      const Text('Tentativas bloqueadas', style: TextStyle(fontSize: 15)),
                      Text(
                        '$_blocked',
                        style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800, color: Colors.greenAccent),
                      ),
                    ],
                  ),
                ),
              ),
              if (_vpnOn) ...[
                const SizedBox(height: 8),
                Text(
                  'Consultas DNS vistas: $_dnsSeen  •  DNS da rede: ${_activeDns.isEmpty ? "nenhum" : _activeDns}',
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 11, color: Colors.white38),
                ),
              ],
              const SizedBox(height: 12),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Como funciona', style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
                      const SizedBox(height: 8),
                      const Text(
                        '• Bloqueia sites e apps de conteúdo adulto e chocante (lista atualizada de domínios + filtro de categorias).\n'
                        '• Funciona em qualquer navegador e app que use internet.\n'
                        '• Para desligar, é preciso o PIN — crianças não conseguem desativar.\n'
                        '• Não coleta nem envia nenhum dado: tudo é filtrado no próprio aparelho.',
                        style: TextStyle(fontSize: 13, height: 1.5, color: Colors.white70),
                      ),
                      const SizedBox(height: 10),
                      Text(
                        'IMPORTANTE: para o bloqueio valer 100%, deixe o DNS do sistema em Automático. '
                        'Se ativar DNS privado (Configurações → Rede → DNS privado) '
                        'ou DNS seguro no Chrome/Firefox, o bloqueio pode ser contornado.',
                        style: TextStyle(fontSize: 12, height: 1.5, color: Colors.orange),
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 12),
              _buildChecklist(theme),
              const SizedBox(height: 12),
              _buildAppBlockCard(theme),
              const SizedBox(height: 12),
              _buildOwnerCard(theme),
              const SizedBox(height: 16),
              Text(
                _isOwner
                    ? 'Bloqueio total ativo: VPN travada, apps não podem ser desinstalados e APKs externos estão bloqueados.'
                    : 'Dica: ative "VPN sempre ativa" nas configurações do Android para o bloqueio não poder ser desligado.',
                textAlign: TextAlign.center,
                style: theme.textTheme.bodySmall?.copyWith(color: Colors.white38),
              ),
            ],
          ),
          ),
        ),
      ),
    );
  }

  Widget _buildChecklist(ThemeData theme) {
    Widget step({
      required IconData icon,
      required String title,
      required String desc,
      required bool done,
      String? buttonLabel,
      VoidCallback? onTap,
    }) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(
              done ? Icons.check_circle : icon,
              color: done ? Colors.greenAccent : theme.colorScheme.outline,
              size: 26,
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: TextStyle(fontWeight: FontWeight.w700, fontSize: 14,
                        color: done ? Colors.greenAccent : Colors.white),
                  ),
                  const SizedBox(height: 2),
                  Text(desc, style: const TextStyle(fontSize: 12, color: Colors.white60, height: 1.4)),
                  if (buttonLabel != null) ...[
                    const SizedBox(height: 6),
                    TextButton.icon(
                      onPressed: onTap,
                      icon: const Icon(Icons.open_in_new, size: 16),
                      label: Text(buttonLabel, style: const TextStyle(fontSize: 12)),
                      style: TextButton.styleFrom(padding: EdgeInsets.zero, minimumSize: const Size(0, 32)),
                    ),
                  ],
                ],
              ),
            ),
          ],
        ),
      );
    }

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Configuração completa', style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
            const SizedBox(height: 4),
            const Text('Siga os passos para travar tudo — sem precisar de PC.',
                style: TextStyle(fontSize: 12, color: Colors.white54)),
            const Divider(height: 20),
            step(
              icon: Icons.lock_outline,
              title: 'VPN sempre ativa',
              desc: 'Configurações → Rede → VPN → Bloqueador Familiar → ative "VPN sempre ativa" e "Bloquear conexões sem VPN". Impede desligar o bloqueio.',
              done: _vpnOn,
              buttonLabel: 'Abrir ajustes de VPN',
              onTap: () => _ch.invokeMethod('openVpnSettings'),
            ),
            step(
              icon: Icons.phonelink_lock_outlined,
              title: 'Bloqueio de apps adultos',
              desc: 'Ative o serviço de acessibilidade para apps adultos não abrirem.',
              done: _accOn,
              buttonLabel: 'Abrir acessibilidade',
              onTap: () => _ch.invokeMethod('openAccessibilitySettings'),
            ),
            step(
              icon: Icons.download_for_offline_outlined,
              title: 'Bloquear instalação de APKs',
              desc: 'Nesta tela, desative "instalar apps desconhecidos" nos navegadores e gerenciadores de arquivos (Chrome, Arquivos etc.).',
              done: false,
              buttonLabel: 'Abrir ajuste',
              onTap: () => _ch.invokeMethod('openUnknownAppsSettings'),
            ),
            step(
              icon: Icons.shop_outlined,
              title: 'Filtro da Play Store',
              desc: 'Na Play Store: foto do perfil → Configurações → Filtrar conteúdo → restrinja por idade.',
              done: false,
              buttonLabel: 'Abrir Play Store',
              onTap: () => _ch.invokeMethod('openPlayStore'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildAppBlockCard(ThemeData theme) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  _accOn ? Icons.app_blocking : Icons.phonelink_lock_outlined,
                  color: _accOn ? Colors.greenAccent : theme.colorScheme.outline,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    _accOn ? 'APPS BLOCKED: ATIVO' : 'Bloqueio de apps adultos',
                    style: TextStyle(fontWeight: FontWeight.w700, color: _accOn ? Colors.greenAccent : Colors.white),
                  ),
                ),
                Text('$_appBlocked', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w800, color: Colors.greenAccent)),
              ],
            ),
            const SizedBox(height: 6),
            const Text(
              'Impede que apps de conteúdo adulto abram no aparelho, mesmo sem internet.',
              style: TextStyle(fontSize: 12, color: Colors.white60),
            ),
            const SizedBox(height: 10),
            Row(
              children: [
                Expanded(
                  child: _accOn
                      ? const SizedBox.shrink()
                      : FilledButton.tonal(
                          onPressed: () async {
                            await _ch.invokeMethod('openAccessibilitySettings');
                            if (mounted) {
                              ScaffoldMessenger.of(context).showSnackBar(
                                const SnackBar(content: Text('Ative "Bloqueador Familiar" na lista de serviços de acessibilidade.')),
                              );
                            }
                          },
                          child: const Text('Ativar'),
                        ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: () => Navigator.push(
                      context,
                      MaterialPageRoute(builder: (_) => const AppsPage()),
                    ),
                    icon: const Icon(Icons.apps, size: 16),
                    label: const Text('Escolher apps'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildOwnerCard(ThemeData theme) {
    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: _showOwnerInfo,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Icon(
                _isOwner ? Icons.verified_user : Icons.admin_panel_settings_outlined,
                color: _isOwner ? Colors.greenAccent : theme.colorScheme.outline,
                size: 32,
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      _isOwner ? 'BLOQUEIO TOTAL ATIVO' : 'Bloqueio total (opcional)',
                      style: TextStyle(
                        fontWeight: FontWeight.w700,
                        color: _isOwner ? Colors.greenAccent : Colors.white,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      _isOwner
                          ? 'VPN travada + apps protegidos contra desinstalação'
                          : 'Toque para ver como ativar (recomendado)',
                      style: const TextStyle(fontSize: 12, color: Colors.white60),
                    ),
                  ],
                ),
              ),
              Icon(Icons.chevron_right, color: theme.colorScheme.outline),
            ],
          ),
        ),
      ),
    );
  }

  void _showOwnerInfo() {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (ctx) => Padding(
        padding: const EdgeInsets.all(24),
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Bloqueio total', style: Theme.of(ctx).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800)),
              const SizedBox(height: 8),
              const Text(
                'Com o modo Device Owner o app vira administrador do aparelho e consegue:\n\n'
                '• Travar a VPN ligada para sempre (ninguém desliga, nem em ajustes)\n'
                '• Impedir a desinstalação de qualquer app, inclusive deste\n'
                '• Bloquear instalação de APKs de fontes externas\n'
                '• Bloquear criação de contas novas no aparelho\n\n'
                'ATENÇÃO: a ativação é feita pelo PC com um comando adb, UMA vez só. '
                'Se o celular tiver contas Google cadastradas, o Android pode exigir remover '
                'as contas (ou formatar) antes — teste primeiro sem formatação.',
                style: TextStyle(fontSize: 14, height: 1.5),
              ),
              const SizedBox(height: 12),
              const Text(
                'Passo a passo (no PC):\n\n'
                '1. No celular: Configurações → Sobre o telefone → toque 7x em "Número da versão" (ativa o modo desenvolvedor)\n'
                '2. Em "Opções do desenvolvedor", ative "Depuração USB"\n'
                '3. Conecte o celular no PC via cabo e aceite a permissão no celular\n'
                '4. Rode no PC:\n\n'
                '   adb shell dpm set-device-owner com.bloqueador.app/.AdminReceiver\n\n'
                '5. Abra o app de novo — o bloqueio total ativa sozinho',
                style: TextStyle(fontSize: 13, height: 1.6, color: Colors.white70),
              ),
              const SizedBox(height: 16),
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  onPressed: () async {
                    Navigator.pop(ctx);
                    await _ch.invokeMethod('applyLockdown');
                    final owner = await _ch.invokeMethod<bool>('isDeviceOwner') ?? false;
                    if (mounted) setState(() => _isOwner = owner);
                  },
                  child: const Text('Já ativei — verificar'),
                ),
              ),
              const SizedBox(height: 8),
            ],
          ),
        ),
      ),
    );
  }
}
