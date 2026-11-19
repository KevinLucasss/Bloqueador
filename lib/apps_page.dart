import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter/services.dart';

class AppsPage extends StatefulWidget {
  const AppsPage({super.key});
  @override
  State<AppsPage> createState() => _AppsPageState();
}

class _AppsPageState extends State<AppsPage> {
  static const _ch = MethodChannel('com.bloqueador.app/vpn');

  Future<void> _alert(String msg) async {
    try {
      final topic = await _ch.invokeMethod<String>('alertTopic') ?? '';
      if (topic.trim().isEmpty) return;
      final client = HttpClient();
      final req = await client
          .postUrl(Uri.parse('https://ntfy.sh/${topic.trim()}'))
          .timeout(const Duration(seconds: 5));
      req.headers.set('Title', 'Bloqueador Familiar');
      req.headers.set('Priority', 'high');
      req.headers.set('Tags', 'warning');
      req.write(msg);
      await req.close().timeout(const Duration(seconds: 5));
      client.close();
    } catch (_) {}
  }

  bool _loading = true;
  List<Map<String, String>> _apps = [];
  Set<String> _blocked = {};
  String _search = '';
  String _category = 'todas';
  bool _pinOk = false;

  static const _cats = {
    'todas': 'Todas',
    'videochamada': 'Videochamada',
    'mensagens': 'Mensagens',
    'redes': 'Redes sociais',
    'streaming': 'Streaming',
    'jogos': 'Jogos',
    'apostas': 'Apostas/Tigrinho',
    'vicios': 'Vícios',
    'adulto': 'Adulto',
    'outros': 'Outros',
  };

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final apps = await _ch.invokeMethod<List>('installedApps');
      final blocked = await _ch.invokeMethod<List>('userBlockedApps');
      setState(() {
        _apps = (apps ?? [])
            .map((e) => Map<String, String>.from(e as Map))
            .toList();
        _blocked = (blocked ?? []).cast<String>().toSet();
        _loading = false;
      });
    } catch (_) {
      setState(() => _loading = false);
    }
  }

  Future<bool> _ensurePin() async {
    if (_pinOk) return true;
    final pin = await showDialog<String>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) {
        final ctrl = TextEditingController();
        return AlertDialog(
          title: const Text('PIN de proteção'),
          content: TextField(
            controller: ctrl,
            keyboardType: TextInputType.number,
            obscureText: true,
            maxLength: 8,
            decoration: const InputDecoration(labelText: 'Digite o PIN para alterar bloqueios'),
            autofocus: true,
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancelar')),
            FilledButton(
              onPressed: () async {
                final ok = await _ch.invokeMethod<bool>('checkPin', {'pin': ctrl.text}) ?? false;
                if (ok) {
                  Navigator.pop(ctx, ctrl.text);
                } else {
                  _alert('🚨 Tentativa de alterar bloqueios com PIN incorreto');
                  if (ctx.mounted) {
                    ScaffoldMessenger.of(ctx).showSnackBar(
                      const SnackBar(content: Text('PIN incorreto — tentativa registrada.')),
                    );
                  }
                }
              },
              child: const Text('OK'),
            ),
          ],
        );
      },
    );
    if (pin == null) return false;
    _pinOk = true;
    return true;
  }

  Future<void> _toggle(String pkg, bool value) async {
    if (!await _ensurePin()) return;
    if (!mounted) return;
    setState(() {
      if (value) {
        _blocked.add(pkg);
      } else {
        _blocked.remove(pkg);
      }
    });
    await _ch.invokeMethod('setUserBlockedApps', {'apps': _blocked.toList()});
  }

  Future<void> _toggleCategory(String cat, bool value) async {
    if (!await _ensurePin()) return;
    if (!mounted) return;
    final pkgs = _filtered(cat: cat)
        .map((a) => a['pkg']!)
        .toList();
    setState(() {
      for (final p in pkgs) {
        if (value) {
          _blocked.add(p);
        } else {
          _blocked.remove(p);
        }
      }
    });
    await _ch.invokeMethod('setUserBlockedApps', {'apps': _blocked.toList()});
  }

  List<Map<String, String>> _filtered({String? cat}) {
    final c = cat ?? _category;
    return _apps.where((a) {
      if (c != 'todas' && a['category'] != c) return false;
      if (_search.isNotEmpty &&
          !(a['label'] ?? '').toLowerCase().contains(_search.toLowerCase()) &&
          !(a['pkg'] ?? '').toLowerCase().contains(_search.toLowerCase())) {
        return false;
      }
      return true;
    }).toList();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('Apps bloqueados')),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
                  child: TextField(
                    decoration: const InputDecoration(
                      hintText: 'Buscar app...',
                      prefixIcon: Icon(Icons.search),
                      isDense: true,
                    ),
                    onChanged: (v) => setState(() => _search = v),
                  ),
                ),
                SizedBox(
                  height: 44,
                  child: ListView(
                    scrollDirection: Axis.horizontal,
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                    children: _cats.entries.map((e) {
                      final sel = _category == e.key;
                      final count = e.key == 'todas'
                          ? _apps.length
                          : _apps.where((a) => a['category'] == e.key).length;
                      return Padding(
                        padding: const EdgeInsets.only(right: 6),
                        child: FilterChip(
                          label: Text('${e.value} ($count)'),
                          selected: sel,
                          onSelected: (_) => setState(() => _category = e.key),
                        ),
                      );
                    }).toList(),
                  ),
                ),
                if (_category != 'todas')
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    child: Row(
                      children: [
                        TextButton.icon(
                          onPressed: () => _toggleCategory(_category, true),
                          icon: const Icon(Icons.done_all, size: 16),
                          label: const Text('Bloquear categoria'),
                        ),
                        TextButton.icon(
                          onPressed: () => _toggleCategory(_category, false),
                          icon: const Icon(Icons.undo, size: 16),
                          label: const Text('Liberar categoria'),
                        ),
                      ],
                    ),
                  ),
                Expanded(
                  child: _filtered().isEmpty
                      ? const Center(child: Text('Nenhum app encontrado'))
                      : ListView.builder(
                          itemCount: _filtered().length,
                          itemBuilder: (ctx, i) {
                            final a = _filtered()[i];
                            final pkg = a['pkg']!;
                            final blocked = _blocked.contains(pkg);
                            return SwitchListTile(
                              title: Text(a['label'] ?? pkg,
                                  style: const TextStyle(fontSize: 14)),
                              subtitle: Text(
                                '${_cats[a['category']] ?? a['category']} • $pkg',
                                style: const TextStyle(fontSize: 11),
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                              ),
                              value: blocked,
                              onChanged: (v) => _toggle(pkg, v),
                              activeThumbColor: Colors.redAccent,
                            );
                          },
                        ),
                ),
                Container(
                  padding: const EdgeInsets.all(12),
                  color: theme.colorScheme.surfaceContainerHighest,
                  child: Text(
                    '${_blocked.length} app(s) bloqueado(s) — fecham ao abrir, mesmo sem internet.',
                    style: const TextStyle(fontSize: 12, color: Colors.white60),
                    textAlign: TextAlign.center,
                  ),
                ),
              ],
            ),
    );
  }
}
