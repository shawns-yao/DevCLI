#!/bin/bash
# OpenAI provider + prompt cache 自动化测试

echo "=== 测试 1: 切换到 OpenAI provider ==="
echo "/model openai"
sleep 2

echo ""
echo "=== 测试 2: 第一轮对话(建立 cache prefix) ==="
echo "你好,用一句话介绍你自己"
sleep 5

echo ""
echo "=== 测试 3: 第二轮对话(观察 cache 命中) ==="
echo "现在几点了?"
sleep 5

echo ""
echo "=== 测试 4: 第三轮对话(cache 应继续命中) ==="
echo "2+2等于多少?"
sleep 5

echo ""
echo "/exit"
