import 'package:flutter/material.dart';

import 'ui/analyzer_home_screen.dart';

class CheatCatApp extends StatelessWidget {
  const CheatCatApp({super.key});

  @override
  Widget build(BuildContext context) {
    const ink = Color(0xFF17213A);
    const coral = Color(0xFFFF5A63);

    return MaterialApp(
      title: '跑单助手',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: coral,
          primary: coral,
          secondary: ink,
          surface: Colors.white,
        ),
        scaffoldBackgroundColor: const Color(0xFFF5F6FA),
        fontFamilyFallback: const [
          'PingFang SC',
          'Microsoft YaHei',
          'sans-serif',
        ],
      ),
      home: const AnalyzerHomeScreen(),
    );
  }
}
