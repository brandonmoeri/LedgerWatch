interface SkeletonProps {
  width?: string | number;
  height?: string | number;
}

export default function Skeleton({ width = '100%', height = '1em' }: SkeletonProps) {
  return <span className="skeleton" style={{ width, height }} aria-hidden="true" />;
}
