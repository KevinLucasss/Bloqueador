import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:blocker_app/main.dart';

void main() {
  testWidgets('App mostra tela de proteção', (WidgetTester tester) async {
    await tester.pumpWidget(const BloqueadorApp());
    await tester.pumpAndSettle(const Duration(seconds: 3));
    expect(find.text('PROTEÇÃO DESLIGADA'), findsOneWidget);
  });
}
