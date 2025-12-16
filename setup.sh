#!/bin/bash

# Jena-FalkorDB Adapter Setup Script

echo "=================================="
echo "Jena-FalkorDB Adapter Setup"
echo "=================================="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print colored output
print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_info() {
    echo -e "${YELLOW}ℹ $1${NC}"
}

# Check if Java is installed
echo "Checking prerequisites..."
if command -v java &> /dev/null; then
    JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}')
    print_success "Java found: $JAVA_VERSION"
else
    print_error "Java not found. Please install Java 21 or higher."
    exit 1
fi

# Check if Maven is installed
if command -v mvn &> /dev/null; then
    MVN_VERSION=$(mvn -version | head -n 1)
    print_success "Maven found: $MVN_VERSION"
else
    print_error "Maven not found. Please install Maven 3.6 or higher."
    exit 1
fi

# Check if Docker is installed
if command -v docker &> /dev/null; then
    print_success "Docker found"
    DOCKER_AVAILABLE=true
else
    print_info "Docker not found. You'll need to install FalkorDB manually."
    DOCKER_AVAILABLE=false
fi

echo ""
echo "=================================="
echo "Setting up FalkorDB"
echo "=================================="
echo ""

if [ "$DOCKER_AVAILABLE" = true ]; then
    # Check if FalkorDB is already running
    if docker ps | grep -q falkordb; then
        print_info "FalkorDB container is already running"
    else
        echo "Starting FalkorDB container..."
        docker run -d --name falkordb -p 6379:6379 falkordb/falkordb:latest
        
        if [ $? -eq 0 ]; then
            print_success "FalkorDB started successfully on port 6379"
            sleep 3  # Wait for FalkorDB to be ready
        else
            print_error "Failed to start FalkorDB"
            exit 1
        fi
    fi
    
    # Test connection
    echo ""
    echo "Testing FalkorDB connection..."
    if command -v redis-cli &> /dev/null; then
        PING_RESULT=$(redis-cli -p 6379 PING 2>/dev/null)
        if [ "$PING_RESULT" = "PONG" ]; then
            print_success "FalkorDB is responding"
        else
            print_error "FalkorDB is not responding"
        fi
    else
        print_info "redis-cli not found, skipping connection test"
    fi
else
    print_info "Please start FalkorDB manually:"
    echo "  Option 1 - Docker: docker run -p 6379:6379 falkordb/falkordb:latest"
    echo "  Option 2 - Direct: Follow instructions at https://www.falkordb.com/"
fi

echo ""
echo "=================================="
echo "Creating Project Structure"
echo "=================================="
echo ""

# Create directory structure
mkdir -p src/main/java/com/example/jena/falkordb
mkdir -p src/main/resources
mkdir -p src/test/java/com/example/jena/falkordb

print_success "Project directories created"

echo ""
echo "=================================="
echo "Building Project"
echo "=================================="
echo ""

# Build the project
echo "Running Maven build..."
mvn clean install -DskipTests

if [ $? -eq 0 ]; then
    print_success "Build successful!"
else
    print_error "Build failed"
    exit 1
fi

echo ""
echo "=================================="
echo "Setup Complete!"
echo "=================================="
echo ""
echo "Next steps:"
echo "  1. Run the demo: mvn exec:java -Dexec.mainClass=\"com.falkordb.jena.Main\""
echo "  2. Run quick start: mvn exec:java -Dexec.mainClass=\"com.falkordb.jena.QuickStart\""
echo "  3. Run tests: mvn test"
echo ""
echo "To stop FalkorDB (if using Docker):"
echo "  docker stop falkordb"
echo ""
echo "To remove FalkorDB container:"
echo "  docker rm falkordb"
echo ""

print_success "All done! Happy coding!"